package am.ik.mcp.yfinance;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import am.ik.yfinance4j.YFinance;
import am.ik.yfinance4j.YFinanceUrls;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Test helper that mimics the Yahoo Finance endpoints used by yfinance4j (cookie, crumb,
 * chart and quoteSummary) on a local {@link HttpServer}, so tests run without network
 * access.
 */
final class YahooMock {

	static final String CHART_AAPL = """
			{
			  "chart": {
			    "result": [{
			      "meta": {"currency": "USD", "symbol": "AAPL", "exchangeName": "NMS", "regularMarketPrice": 150.0},
			      "timestamp": [1700000000, 1700086400],
			      "indicators": {
			        "quote": [{"open": [148.0, 149.0], "high": [151.0, 152.0], "low": [147.0, 148.5], "close": [150.0, 151.0], "volume": [1000000, 1200000]}],
			        "adjclose": [{"adjclose": [149.5, 150.5]}]
			      }
			    }],
			    "error": null
			  }
			}
			""";

	static final String QUOTE_SUMMARY_AAPL = """
			{
			  "quoteSummary": {
			    "result": [{
			      "price": {"shortName": "Apple Inc.", "longName": "Apple Inc.", "currency": "USD", "exchange": "NMS", "quoteType": "EQUITY", "regularMarketPrice": {"raw": 150.0, "fmt": "150.00"}},
			      "summaryDetail": {"marketCap": {"raw": 2500000000000, "fmt": "2.5T"}},
			      "financialData": {"currentPrice": {"raw": 150.0, "fmt": "150.00"}},
			      "summaryProfile": {"sector": "Technology", "industry": "Consumer Electronics"}
			    }],
			    "error": null
			  }
			}
			""";

	private YahooMock() {
	}

	static void registerAuth(HttpServer server) {
		server.createContext("/cookie", exchange -> {
			exchange.getResponseHeaders().set("Set-Cookie", "test-cookie=abc123; path=/");
			exchange.sendResponseHeaders(200, -1);
			exchange.close();
		});
		server.createContext("/crumb", text("test-crumb-value"));
	}

	static void registerChart(HttpServer server, String symbol, String json) {
		server.createContext("/v8/finance/chart/" + symbol, json(json));
	}

	static void registerQuoteSummary(HttpServer server, String symbol, String json) {
		server.createContext("/v10/finance/quoteSummary/" + symbol, json(json));
	}

	static YFinance client(String baseUrl) {
		YFinanceUrls urls = YFinanceUrls.builder()
			.cookieUrl(baseUrl + "/cookie")
			.crumbUrl(baseUrl + "/crumb")
			.chartUrl(baseUrl + "/v8/finance/chart/{ticker}")
			.quoteSummaryUrl(baseUrl + "/v10/finance/quoteSummary/{ticker}")
			.build();
		RestClient restClient = RestClient.builder()
			.requestFactory(new JdkClientHttpRequestFactory())
			.defaultHeader("User-Agent", "Mozilla/5.0")
			.build();
		return new YFinance(restClient, urls);
	}

	private static HttpHandler text(String body) {
		return exchange -> {
			byte[] payload = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
			exchange.sendResponseHeaders(200, payload.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(payload);
			}
		};
	}

	private static HttpHandler json(String body) {
		return exchange -> {
			byte[] payload = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, payload.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(payload);
			}
		};
	}

}
