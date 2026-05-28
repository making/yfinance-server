package am.ik.mcp.yfinance;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for the yfinance4j client. The four URLs default to the public Yahoo
 * Finance endpoints and can be overridden (for example to point at a mock server in
 * tests). The {@code {ticker}} placeholder in the chart and quoteSummary URLs is
 * substituted by the library with the requested symbol.
 */
@ConfigurationProperties(prefix = "yfinance")
public record YfinanceProperties(@DefaultValue("https://fc.yahoo.com") String cookieUrl,
		@DefaultValue("https://query1.finance.yahoo.com/v1/test/getcrumb") String crumbUrl,
		@DefaultValue("https://query2.finance.yahoo.com/v8/finance/chart/{ticker}") String chartUrl,
		@DefaultValue("https://query2.finance.yahoo.com/v10/finance/quoteSummary/{ticker}") String quoteSummaryUrl) {
}
