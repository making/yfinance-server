package am.ik.mcp.yfinance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
class YfinanceServerIntegrationTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final HttpServer MOCK_SERVER = createMockServer();

	@Autowired
	private RestTestClient client;

	private static HttpServer createMockServer() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			YahooMock.registerAuth(server);
			YahooMock.registerChart(server, "AAPL", YahooMock.CHART_AAPL);
			YahooMock.registerQuoteSummary(server, "AAPL", YahooMock.QUOTE_SUMMARY_AAPL);
			server.start();
			return server;
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	@DynamicPropertySource
	static void yfinanceUrls(DynamicPropertyRegistry registry) {
		String base = "http://127.0.0.1:" + MOCK_SERVER.getAddress().getPort();
		registry.add("yfinance.cookie-url", () -> base + "/cookie");
		registry.add("yfinance.crumb-url", () -> base + "/crumb");
		registry.add("yfinance.chart-url", () -> base + "/v8/finance/chart/{ticker}");
		registry.add("yfinance.quote-summary-url", () -> base + "/v10/finance/quoteSummary/{ticker}");
	}

	@AfterAll
	static void stopServer() {
		MOCK_SERVER.stop(0);
	}

	@Test
	void shouldListAllTools() throws Exception {
		String sessionId = initializeSession();
		confirmInitialized(sessionId);

		JsonNode result = callRpc(sessionId, "tools/list", null);

		List<String> toolNames = result.path("tools")
			.findValues("name")
			.stream()
			.map(JsonNode::asText)
			.sorted()
			.toList();
		assertThat(toolNames).containsExactly("get_stock_history", "get_stock_info", "get_stock_quote");
	}

	@Test
	void shouldReturnHistory() throws Exception {
		String sessionId = initializeSession();
		confirmInitialized(sessionId);

		JsonNode payload = callTool(sessionId, "get_stock_history", Map.of("symbols", List.of("AAPL")));

		assertThat(payload).isEqualTo(MAPPER.readTree(
				"""
						{"results":[{"symbol":"AAPL","rows":[\
						{"timestamp":"2023-11-14T22:13:20Z","open":148.0,"high":151.0,"low":147.0,"close":150.0,"adjClose":149.5,"volume":1000000,"dividends":0,"stockSplits":0},\
						{"timestamp":"2023-11-15T22:13:20Z","open":149.0,"high":152.0,"low":148.5,"close":151.0,"adjClose":150.5,"volume":1200000,"dividends":0,"stockSplits":0}\
						],"error":null}]}"""));
	}

	@Test
	void shouldReturnInfo() throws Exception {
		String sessionId = initializeSession();
		confirmInitialized(sessionId);

		JsonNode payload = callTool(sessionId, "get_stock_info", Map.of("symbols", List.of("AAPL")));

		assertThat(payload).isEqualTo(MAPPER.readTree("""
				{"results":[{"symbol":"AAPL","shortName":"Apple Inc.","longName":"Apple Inc.","currency":"USD",\
				"exchange":"NMS","quoteType":"EQUITY","sector":"Technology","industry":"Consumer Electronics",\
				"currentPrice":150.0,"marketCap":2500000000000,"regularMarketPrice":150.0,"error":null}]}"""));
	}

	@Test
	void shouldReturnQuote() throws Exception {
		String sessionId = initializeSession();
		confirmInitialized(sessionId);

		JsonNode payload = callTool(sessionId, "get_stock_quote", Map.of("symbols", List.of("AAPL")));

		assertThat(payload).isEqualTo(MAPPER.readTree("""
				{"results":[{"symbol":"AAPL","price":150.0,"currency":"USD","error":null}]}"""));
	}

	private String initializeSession() {
		HttpHeaders headers = this.client.post()
			.uri("/mcp")
			.contentType(MediaType.APPLICATION_JSON)
			.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
			.body(Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize", "params",
					Map.of("protocolVersion", "2024-11-05", "capabilities", Map.of(), "clientInfo",
							Map.of("name", "junit", "version", "1"))))
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.returnResult()
			.getResponseHeaders();
		String sessionId = headers.getFirst("Mcp-Session-Id");
		assertThat(sessionId).as("Mcp-Session-Id header from initialize").isNotBlank();
		return sessionId;
	}

	private void confirmInitialized(String sessionId) {
		this.client.post()
			.uri("/mcp")
			.contentType(MediaType.APPLICATION_JSON)
			.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
			.header("Mcp-Session-Id", sessionId)
			.body(Map.of("jsonrpc", "2.0", "method", "notifications/initialized"))
			.exchange()
			.expectStatus()
			.isAccepted();
	}

	private JsonNode callTool(String sessionId, String toolName, Map<String, Object> arguments) throws Exception {
		JsonNode rpcResult = callRpc(sessionId, "tools/call", Map.of("name", toolName, "arguments", arguments));
		assertThat(rpcResult.path("isError").asBoolean()).as("tool error flag").isFalse();
		String inner = rpcResult.path("content").get(0).path("text").asText();
		return MAPPER.readTree(inner);
	}

	private JsonNode callRpc(String sessionId, String method, Map<String, Object> params) throws Exception {
		Map<String, Object> request = (params != null)
				? Map.of("jsonrpc", "2.0", "id", 2, "method", method, "params", params)
				: Map.of("jsonrpc", "2.0", "id", 2, "method", method);
		String body = this.client.post()
			.uri("/mcp")
			.contentType(MediaType.APPLICATION_JSON)
			.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
			.header("Mcp-Session-Id", sessionId)
			.body(request)
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody(String.class)
			.returnResult()
			.getResponseBody();
		assertThat(body).isNotNull();
		String json = extractSseData(body);
		return MAPPER.readTree(json).path("result");
	}

	private static String extractSseData(String sseBody) {
		return sseBody.lines()
			.filter(line -> line.startsWith("data:"))
			.findFirst()
			.map(line -> line.substring("data:".length()))
			.orElse(sseBody);
	}

}
