package am.ik.mcp.yfinance;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;

import am.ik.yfinance4j.YFinance;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.core.task.SimpleAsyncTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;

class StockServiceTest {

	private HttpServer server;

	private StockService service;

	@BeforeEach
	void setUp() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.start();
		String baseUrl = "http://127.0.0.1:" + this.server.getAddress().getPort();
		YahooMock.registerAuth(this.server);
		YahooMock.registerChart(this.server, "AAPL", YahooMock.CHART_AAPL);
		YahooMock.registerQuoteSummary(this.server, "AAPL", YahooMock.QUOTE_SUMMARY_AAPL);
		YFinance yFinance = YahooMock.client(baseUrl);
		SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor();
		executor.setVirtualThreads(true);
		this.service = new StockService(yFinance, executor);
	}

	@AfterEach
	void tearDown() {
		this.server.stop(0);
	}

	@Test
	void shouldFetchHistoryForSingleSymbol() {
		StockService.StockHistoryResponse response = this.service.getStockHistory(List.of("AAPL"), null, null, null,
				null);

		assertThat(response.results()).hasSize(1);
		StockService.SymbolHistory history = response.results().get(0);
		assertThat(history.symbol()).isEqualTo("AAPL");
		assertThat(history.error()).isNull();
		assertThat(history.rows()).hasSize(2);

		StockService.HistoryRow first = history.rows().get(0);
		assertThat(first.timestamp()).isEqualTo("2023-11-14T22:13:20Z");
		assertThat(first.open()).isEqualByComparingTo("148.0");
		assertThat(first.high()).isEqualByComparingTo("151.0");
		assertThat(first.low()).isEqualByComparingTo("147.0");
		assertThat(first.close()).isEqualByComparingTo("150.0");
		assertThat(first.adjClose()).isEqualByComparingTo("149.5");
		assertThat(first.volume()).isEqualTo(1000000L);
		assertThat(first.dividends()).isEqualByComparingTo("0");
		assertThat(first.stockSplits()).isEqualByComparingTo("0");

		StockService.HistoryRow second = history.rows().get(1);
		assertThat(second.timestamp()).isEqualTo("2023-11-15T22:13:20Z");
		assertThat(second.open()).isEqualByComparingTo("149.0");
		assertThat(second.close()).isEqualByComparingTo("151.0");
		assertThat(second.adjClose()).isEqualByComparingTo("150.5");
		assertThat(second.volume()).isEqualTo(1200000L);
	}

	@Test
	void shouldPreserveSymbolOrder() {
		YahooMock.registerChart(this.server, "MSFT", YahooMock.CHART_AAPL);

		StockService.StockHistoryResponse response = this.service.getStockHistory(List.of("AAPL", "MSFT"), null, null,
				null, null);

		assertThat(response.results()).extracting(StockService.SymbolHistory::symbol).containsExactly("AAPL", "MSFT");
	}

	@Test
	void shouldReportErrorForUnknownSymbolWithoutAbortingOthers() {
		StockService.StockHistoryResponse response = this.service.getStockHistory(List.of("AAPL", "UNKNOWN"), null,
				null, null, null);

		assertThat(response.results()).hasSize(2);
		StockService.SymbolHistory ok = response.results().get(0);
		assertThat(ok.symbol()).isEqualTo("AAPL");
		assertThat(ok.error()).isNull();
		assertThat(ok.rows()).hasSize(2);

		StockService.SymbolHistory failed = response.results().get(1);
		assertThat(failed.symbol()).isEqualTo("UNKNOWN");
		assertThat(failed.error()).isNotNull();
		assertThat(failed.rows()).isEmpty();
	}

	@Test
	void shouldFetchInfo() {
		StockService.StockInfoResponse response = this.service.getStockInfo(List.of("AAPL"));

		assertThat(response.results()).hasSize(1);
		StockService.StockInfoResult info = response.results().get(0);
		assertThat(info.symbol()).isEqualTo("AAPL");
		assertThat(info.shortName()).isEqualTo("Apple Inc.");
		assertThat(info.longName()).isEqualTo("Apple Inc.");
		assertThat(info.currency()).isEqualTo("USD");
		assertThat(info.exchange()).isEqualTo("NMS");
		assertThat(info.quoteType()).isEqualTo("EQUITY");
		assertThat(info.sector()).isEqualTo("Technology");
		assertThat(info.industry()).isEqualTo("Consumer Electronics");
		assertThat(info.currentPrice()).isEqualByComparingTo("150.0");
		assertThat(info.marketCap()).isEqualByComparingTo("2500000000000");
		assertThat(info.regularMarketPrice()).isEqualByComparingTo("150.0");
		assertThat(info.error()).isNull();
	}

	@Test
	void shouldFetchQuote() {
		StockService.StockQuoteResponse response = this.service.getStockQuote(List.of("AAPL"));

		assertThat(response.results()).hasSize(1);
		StockService.StockQuoteResult quote = response.results().get(0);
		assertThat(quote.symbol()).isEqualTo("AAPL");
		assertThat(quote.price()).isEqualByComparingTo("150.0");
		assertThat(quote.currency()).isEqualTo("USD");
		assertThat(quote.error()).isNull();
	}

}
