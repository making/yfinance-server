# yfinance-server

An MCP (Model Context Protocol) server that exposes stock market data from
Yahoo Finance. It wraps the [yfinance4j](https://github.com/making/yfinance4j)
library and provides three tools:

- `get_stock_history` — historical OHLCV (open/high/low/close/volume) data for
  one or more symbols.
- `get_stock_info` — company information and current price (short/long name,
  currency, exchange, sector, industry, market cap, etc.).
- `get_stock_quote` — the latest price only, for a lightweight lookup.

Every tool accepts multiple ticker symbols in a single call. Symbols are fetched
concurrently and the results are returned together, one entry per symbol in the
same order as the request. A failure for one symbol does not abort the others;
only that entry carries an `error`.

Use this server when an MCP client (Claude Desktop, custom agents, etc.) needs
to read stock prices or company information as part of its workflow. The server
speaks the MCP Streamable HTTP transport on `POST /mcp`.

Built with Spring Boot 4 + Spring AI 2.0. Supports GraalVM native image.

## Requirements

- JDK 25 (GraalVM 25 if you want to build a native image)
- Maven Wrapper bundled in the repository (`./mvnw`)

## Build & Run

### JVM

```sh
./mvnw spring-boot:run
```

### Native image

```sh
./mvnw -DskipTests native:compile
./target/yfinance-server
```

The server listens on port `8091` by default (override with `PORT`).

## Tools

### `get_stock_history`

| name       | type         | required | default | description                                                              |
|------------|--------------|----------|---------|--------------------------------------------------------------------------|
| `symbols`  | string array | yes      | —       | ticker symbols, e.g. `["AAPL","MSFT","7203.T"]`                          |
| `period`   | string       | no       | `1mo`   | `1d,5d,1mo,3mo,6mo,1y,2y,5y,10y,ytd,max`. Ignored when `start` and `end` are both set. |
| `interval` | string       | no       | `1d`    | `1m,2m,5m,15m,30m,60m,90m,1h,1d,5d,1wk,1mo,3mo`                           |
| `start`    | string       | no       | none    | start instant, ISO-8601 (e.g. `2024-01-01T00:00:00Z`)                    |
| `end`      | string       | no       | none    | end instant, ISO-8601 (e.g. `2024-06-01T00:00:00Z`)                      |

Returns a `results` array of `{ symbol, rows, error }`. Each row contains:
`timestamp` (ISO-8601), `open`, `high`, `low`, `close`, `adjClose`, `volume`,
`dividends`, `stockSplits`. On failure `rows` is empty and `error` is set.

### `get_stock_info`

| name      | type         | required | default | description                                     |
|-----------|--------------|----------|---------|-------------------------------------------------|
| `symbols` | string array | yes      | —       | ticker symbols, e.g. `["AAPL","MSFT","7203.T"]` |

Returns a `results` array of `{ symbol, shortName, longName, currency,
exchange, quoteType, sector, industry, currentPrice, marketCap,
regularMarketPrice, error }`. Fields that Yahoo does not return are `null`. On
failure all fields except `symbol` are `null` and `error` is set.

### `get_stock_quote`

| name      | type         | required | default | description                                     |
|-----------|--------------|----------|---------|-------------------------------------------------|
| `symbols` | string array | yes      | —       | ticker symbols, e.g. `["AAPL","MSFT","7203.T"]` |

Returns a `results` array of `{ symbol, price, currency, error }`. On failure
`price` and `currency` are `null` and `error` is set.

## Testing with curl

The MCP Streamable HTTP transport requires a handshake before any tool call:

1. `POST /mcp` with method `initialize`. The response carries an
   `Mcp-Session-Id` header that must be sent on every subsequent request.
2. `POST /mcp` with method `notifications/initialized` to confirm the session.
3. `POST /mcp` with method `tools/list` or `tools/call`.

The examples below assume the server is running on `http://localhost:8091`. A
tool result is returned inside the JSON-RPC `result.content[0].text` field as a
JSON string.

### 1. Initialize the session

```sh
curl -i -X POST http://localhost:8091/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json,text/event-stream" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "initialize",
    "params": {
      "protocolVersion": "2024-11-05",
      "capabilities": {},
      "clientInfo": {"name": "curl", "version": "1"}
    }
  }'
```

Look for the response header `Mcp-Session-Id: <uuid>` and reuse the UUID below.

```sh
SESSION_ID=<paste-uuid-here>
```

### 2. Confirm initialization

```sh
curl -X POST http://localhost:8091/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json,text/event-stream" \
  -H "Mcp-Session-Id: $SESSION_ID" \
  -d '{"jsonrpc": "2.0", "method": "notifications/initialized"}'
```

This returns HTTP 202 with no body.

### 3. List the available tools

```sh
curl -X POST http://localhost:8091/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json,text/event-stream" \
  -H "Mcp-Session-Id: $SESSION_ID" \
  -d '{"jsonrpc": "2.0", "id": 2, "method": "tools/list"}'
```

### 4. Get the latest quote for several symbols

```sh
curl -X POST http://localhost:8091/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json,text/event-stream" \
  -H "Mcp-Session-Id: $SESSION_ID" \
  -d '{
    "jsonrpc": "2.0",
    "id": 3,
    "method": "tools/call",
    "params": {
      "name": "get_stock_quote",
      "arguments": {"symbols": ["AAPL", "MSFT"]}
    }
  }'
```

The `result.content[0].text` holds:

```json
{"results":[{"symbol":"AAPL","price":310.85,"currency":"USD","error":null},{"symbol":"MSFT","price":412.67,"currency":"USD","error":null}]}
```

### 5. Get historical OHLCV data

```sh
curl -X POST http://localhost:8091/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json,text/event-stream" \
  -H "Mcp-Session-Id: $SESSION_ID" \
  -d '{
    "jsonrpc": "2.0",
    "id": 4,
    "method": "tools/call",
    "params": {
      "name": "get_stock_history",
      "arguments": {"symbols": ["AAPL"], "period": "5d", "interval": "1d"}
    }
  }'
```

To request an explicit date range instead of a period, set `start` and `end`
(`period` is then ignored):

```sh
curl -X POST http://localhost:8091/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json,text/event-stream" \
  -H "Mcp-Session-Id: $SESSION_ID" \
  -d '{
    "jsonrpc": "2.0",
    "id": 5,
    "method": "tools/call",
    "params": {
      "name": "get_stock_history",
      "arguments": {
        "symbols": ["AAPL"],
        "interval": "1wk",
        "start": "2024-01-01T00:00:00Z",
        "end": "2024-06-01T00:00:00Z"
      }
    }
  }'
```

### 6. Get company information

```sh
curl -X POST http://localhost:8091/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json,text/event-stream" \
  -H "Mcp-Session-Id: $SESSION_ID" \
  -d '{
    "jsonrpc": "2.0",
    "id": 6,
    "method": "tools/call",
    "params": {
      "name": "get_stock_info",
      "arguments": {"symbols": ["AAPL"]}
    }
  }'
```

### One-shot script

The full handshake plus a tool call as a single script:

```sh
#!/usr/bin/env bash
set -euo pipefail
BASE=http://localhost:8091/mcp

INIT=$(curl -s -i -X POST "$BASE" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json,text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"curl","version":"1"}}}')
SESSION_ID=$(printf '%s' "$INIT" | awk -F': ' 'tolower($1)=="mcp-session-id"{print $2}' | tr -d '\r\n')

curl -s -o /dev/null -X POST "$BASE" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json,text/event-stream" \
  -H "Mcp-Session-Id: $SESSION_ID" \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'

curl -s -X POST "$BASE" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json,text/event-stream" \
  -H "Mcp-Session-Id: $SESSION_ID" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"get_stock_quote","arguments":{"symbols":["AAPL","MSFT"]}}}'
```

## Tests

```sh
./mvnw test
```

The tests run without network access: an in-process
`com.sun.net.httpserver.HttpServer` mimics the Yahoo Finance endpoints, and the
client URLs are overridden via the `yfinance.*` properties. They cover the tool
service directly and the full MCP handshake end-to-end.
