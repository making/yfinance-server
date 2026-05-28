package am.ik.mcp.yfinance;

import am.ik.yfinance4j.YFinance;
import am.ik.yfinance4j.YFinanceUrls;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.zalando.logbook.Logbook;
import org.zalando.logbook.spring.LogbookClientHttpRequestInterceptor;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(YfinanceProperties.class)
public class AppConfig {

	private static final String USER_AGENT = "Mozilla/5.0";

	@Bean
	public RestClientCustomizer restClientCustomizer(Logbook logbook) {
		return builder -> builder.requestInterceptor(new LogbookClientHttpRequestInterceptor(logbook));
	}

	@Bean
	public YFinance yFinance(RestClient.Builder builder, YfinanceProperties properties) {
		RestClient restClient = builder.clone()
			.requestFactory(new JdkClientHttpRequestFactory())
			.defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
			.build();
		YFinanceUrls urls = YFinanceUrls.builder()
			.cookieUrl(properties.cookieUrl())
			.crumbUrl(properties.crumbUrl())
			.chartUrl(properties.chartUrl())
			.quoteSummaryUrl(properties.quoteSummaryUrl())
			.build();
		return new YFinance(restClient, urls);
	}

}
