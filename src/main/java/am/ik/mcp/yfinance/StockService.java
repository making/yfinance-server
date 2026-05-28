package am.ik.mcp.yfinance;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import am.ik.yfinance4j.Interval;
import am.ik.yfinance4j.Period;
import am.ik.yfinance4j.YFinance;
import am.ik.yfinance4j.chart.ChartRequest;
import am.ik.yfinance4j.chart.HistoryRecord;
import am.ik.yfinance4j.quote.QuoteSummaryModule;
import am.ik.yfinance4j.quote.StockInfo;
import org.jspecify.annotations.Nullable;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;

/**
 * MCP tool service that fetches stock data from Yahoo Finance via yfinance4j. Each tool
 * accepts multiple symbols and fetches them concurrently on the Spring-managed task
 * executor (virtual threads when enabled), preserving input order; a failure for one
 * symbol does not abort the others and is reported via the per-symbol {@code error}
 * field.
 */
@Service
public class StockService {

	private final YFinance yFinance;

	private final AsyncTaskExecutor taskExecutor;

	public StockService(YFinance yFinance, @Qualifier("applicationTaskExecutor") AsyncTaskExecutor taskExecutor) {
		this.yFinance = yFinance;
		this.taskExecutor = taskExecutor;
	}

	/**
	 * A single historical OHLCV data point.
	 */
	public record HistoryRow(String timestamp, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close,
			BigDecimal adjClose, long volume, BigDecimal dividends, BigDecimal stockSplits) {
	}

	/**
	 * Historical data for one symbol. On failure {@code rows} is empty and {@code error}
	 * carries the failure message.
	 */
	public record SymbolHistory(String symbol, List<HistoryRow> rows, @Nullable String error) {
	}

	/**
	 * Aggregated history response, one {@link SymbolHistory} per requested symbol in
	 * input order.
	 */
	public record StockHistoryResponse(List<SymbolHistory> results) {
	}

	/**
	 * Company information and current price for one symbol. On failure all fields except
	 * {@code symbol} are {@code null} and {@code error} carries the failure message.
	 */
	public record StockInfoResult(String symbol, @Nullable String shortName, @Nullable String longName,
			@Nullable String currency, @Nullable String exchange, @Nullable String quoteType, @Nullable String sector,
			@Nullable String industry, @Nullable BigDecimal currentPrice, @Nullable BigDecimal marketCap,
			@Nullable BigDecimal regularMarketPrice, @Nullable String error) {
	}

	/**
	 * Aggregated info response, one {@link StockInfoResult} per requested symbol in input
	 * order.
	 */
	public record StockInfoResponse(List<StockInfoResult> results) {
	}

	/**
	 * Lightweight current price for one symbol. On failure {@code price}/{@code currency}
	 * are {@code null} and {@code error} carries the failure message.
	 */
	public record StockQuoteResult(String symbol, @Nullable BigDecimal price, @Nullable String currency,
			@Nullable String error) {
	}

	/**
	 * Aggregated quote response, one {@link StockQuoteResult} per requested symbol in
	 * input order.
	 */
	public record StockQuoteResponse(List<StockQuoteResult> results) {
	}

	@McpTool(name = "get_stock_history",
			description = "Fetch historical OHLCV data for one or more stock symbols from Yahoo Finance")
	public StockHistoryResponse getStockHistory(
			@ToolParam(description = "Ticker symbols, e.g. [\"AAPL\",\"MSFT\",\"7203.T\"]") List<String> symbols,
			@ToolParam(
					description = "Period: 1d,5d,1mo,3mo,6mo,1y,2y,5y,10y,ytd,max (default 1mo). Ignored when start and end are both set.",
					required = false) @Nullable String period,
			@ToolParam(description = "Interval: 1m,2m,5m,15m,30m,60m,90m,1h,1d,5d,1wk,1mo,3mo (default 1d)",
					required = false) @Nullable String interval,
			@ToolParam(description = "Start instant (ISO-8601, e.g. 2024-01-01T00:00:00Z)",
					required = false) @Nullable String start,
			@ToolParam(description = "End instant (ISO-8601, e.g. 2024-06-01T00:00:00Z)",
					required = false) @Nullable String end) {
		ChartRequest request = ChartRequest.builder()
			.period(resolvePeriod(period))
			.interval(resolveInterval(interval))
			.start(start != null ? Instant.parse(start) : null)
			.end(end != null ? Instant.parse(end) : null)
			.build();
		return new StockHistoryResponse(runForEach(symbols, symbol -> historyForSymbol(symbol, request)));
	}

	@McpTool(name = "get_stock_info",
			description = "Fetch company information and current price for one or more stock symbols from Yahoo Finance")
	public StockInfoResponse getStockInfo(
			@ToolParam(description = "Ticker symbols, e.g. [\"AAPL\",\"MSFT\",\"7203.T\"]") List<String> symbols) {
		return new StockInfoResponse(runForEach(symbols, this::infoForSymbol));
	}

	@McpTool(name = "get_stock_quote",
			description = "Fetch the latest price for one or more stock symbols from Yahoo Finance")
	public StockQuoteResponse getStockQuote(
			@ToolParam(description = "Ticker symbols, e.g. [\"AAPL\",\"MSFT\",\"7203.T\"]") List<String> symbols) {
		return new StockQuoteResponse(runForEach(symbols, this::quoteForSymbol));
	}

	private <T> List<T> runForEach(List<String> symbols, Function<String, T> mapper) {
		// Submit every symbol first so they run concurrently, then join in input order.
		List<CompletableFuture<T>> futures = symbols.stream()
			.map(symbol -> this.taskExecutor.submitCompletable(() -> mapper.apply(symbol)))
			.toList();
		return futures.stream().map(CompletableFuture::join).toList();
	}

	private SymbolHistory historyForSymbol(String symbol, ChartRequest request) {
		try {
			List<HistoryRow> rows = this.yFinance.ticker(symbol)
				.history(request)
				.stream()
				.map(StockService::toRow)
				.toList();
			return new SymbolHistory(symbol, rows, null);
		}
		catch (Exception ex) {
			return new SymbolHistory(symbol, List.of(), errorMessage(ex));
		}
	}

	private StockInfoResult infoForSymbol(String symbol) {
		try {
			StockInfo info = this.yFinance.ticker(symbol).info();
			return new StockInfoResult(symbol, info.shortNameNullable(), info.longNameNullable(),
					info.currencyNullable(), info.exchangeNullable(), info.quoteTypeNullable(), info.sectorNullable(),
					info.industryNullable(), info.currentPriceNullable(), info.marketCapNullable(),
					info.regularMarketPriceNullable(), null);
		}
		catch (Exception ex) {
			return new StockInfoResult(symbol, null, null, null, null, null, null, null, null, null, null,
					errorMessage(ex));
		}
	}

	private StockQuoteResult quoteForSymbol(String symbol) {
		try {
			StockInfo info = this.yFinance.ticker(symbol).info(QuoteSummaryModule.PRICE);
			BigDecimal regular = info.regularMarketPriceNullable();
			BigDecimal price = (regular != null) ? regular : info.currentPriceNullable();
			return new StockQuoteResult(symbol, price, info.currencyNullable(), null);
		}
		catch (Exception ex) {
			return new StockQuoteResult(symbol, null, null, errorMessage(ex));
		}
	}

	private static HistoryRow toRow(HistoryRecord record) {
		return new HistoryRow(record.timestamp().toString(), record.open(), record.high(), record.low(), record.close(),
				record.adjClose(), record.volume(), record.dividends(), record.stockSplits());
	}

	private static Period resolvePeriod(@Nullable String value) {
		if (value == null) {
			return Period.ONE_MONTH;
		}
		for (Period period : Period.values()) {
			if (period.value().equalsIgnoreCase(value) || period.name().equalsIgnoreCase(value)) {
				return period;
			}
		}
		throw new IllegalArgumentException("Unknown period: " + value);
	}

	private static Interval resolveInterval(@Nullable String value) {
		if (value == null) {
			return Interval.ONE_DAY;
		}
		for (Interval interval : Interval.values()) {
			if (interval.value().equalsIgnoreCase(value) || interval.name().equalsIgnoreCase(value)) {
				return interval;
			}
		}
		throw new IllegalArgumentException("Unknown interval: " + value);
	}

	private static String errorMessage(Throwable ex) {
		String message = ex.getMessage();
		return (message != null && !message.isBlank()) ? message : ex.getClass().getSimpleName();
	}

}
