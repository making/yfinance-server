package am.ik.mcp.yfinance;

import am.ik.yfinance4j.chart.ChartResponse;
import am.ik.yfinance4j.quote.QuoteSummaryResponse;
import org.jspecify.annotations.Nullable;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

@Configuration(proxyBeanMethods = false)
@ImportRuntimeHints(NativeConfig.NativeRuntimeHints.class)
@RegisterReflectionForBinding({ StockService.StockHistoryResponse.class, StockService.SymbolHistory.class,
		StockService.HistoryRow.class, StockService.StockInfoResponse.class, StockService.StockInfoResult.class,
		StockService.StockQuoteResponse.class, StockService.StockQuoteResult.class, ChartResponse.class,
		ChartResponse.Chart.class, ChartResponse.Result.class, ChartResponse.Meta.class, ChartResponse.Indicators.class,
		ChartResponse.Quote.class, ChartResponse.AdjClose.class, ChartResponse.Events.class,
		ChartResponse.Dividend.class, ChartResponse.Split.class, ChartResponse.ChartError.class,
		QuoteSummaryResponse.class, QuoteSummaryResponse.QuoteSummary.class,
		QuoteSummaryResponse.QuoteSummaryError.class })
public class NativeConfig {

	static class NativeRuntimeHints implements RuntimeHintsRegistrar {

		private static final String[] SPRING_AI_REFLECTIVE_CLASSES = {
				"org.springframework.ai.mcp.annotation.context.DefaultMetaProvider" };

		@Override
		public void registerHints(RuntimeHints hints, @Nullable ClassLoader classLoader) {
			for (String type : SPRING_AI_REFLECTIVE_CLASSES) {
				hints.reflection()
					.registerType(TypeReference.of(type), MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
							MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
			}
		}

	}

}
