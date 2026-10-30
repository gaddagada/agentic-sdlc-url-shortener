package com.agenticsdlc.shortener.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import com.agenticsdlc.shortener.config.ShortenerProperties.Security;
import com.agenticsdlc.shortener.service.ClientIpResolver;
import com.agenticsdlc.shortener.support.TestProperties;

import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
class ApiKeyConfigTest {

    private static final String OPEN_WARNING = "shortener.security.api-key is not set";

    @Test
    void withoutAKeyTheApiIsOpenAndOneWarningIsLogged(CapturedOutput output) {
        FilterRegistrationBean<ApiKeyFilter> registration = register(null);

        assertThat(registration.getFilter().isEnforcing()).isFalse();
        assertThat(output.getOut().split(OPEN_WARNING, -1)).hasSize(2); // exactly one occurrence
        assertThat(output.getOut()).contains("WARN");
    }

    @Test
    void withAKeyTheFilterEnforcesItBeforeTheRateLimiter(CapturedOutput output) {
        FilterRegistrationBean<ApiKeyFilter> registration = register("x".repeat(40));

        assertThat(registration.getFilter().isEnforcing()).isTrue();
        assertThat(registration.getUrlPatterns()).containsExactly("/*");
        assertThat(registration.getOrder()).isLessThan(rateLimitFilterOrder());
        assertThat(output.getOut()).doesNotContain(OPEN_WARNING).doesNotContain("x".repeat(40));
    }

    @Test
    void aShortKeyIsAcceptedWithAWarning(CapturedOutput output) {
        FilterRegistrationBean<ApiKeyFilter> registration = register("short-key");

        assertThat(registration.getFilter().isEnforcing()).isTrue();
        assertThat(output.getOut()).contains("shorter than 32 characters").doesNotContain("short-key");
    }

    @Test
    void theKeyIsNeverPrintedWithTheProperties() {
        assertThat(new Security("top-secret-value").toString()).doesNotContain("top-secret-value");
        assertThat(new Security(null).toString()).contains("<unset>");
    }

    private static FilterRegistrationBean<ApiKeyFilter> register(String apiKey) {
        ShortenerProperties defaults = TestProperties.defaults();
        ShortenerProperties properties = new ShortenerProperties(defaults.baseUrl(), defaults.codeLength(),
                defaults.maxTtl(), true, false, Duration.ofSeconds(2), false, defaults.rateLimit(),
                defaults.analytics(), new Security(apiKey));
        return new ApiKeyConfig().apiKeyFilter(properties, JsonMapper.builder().build());
    }

    private static int rateLimitFilterOrder() {
        return new RateLimitConfig().rateLimitFilter(new TokenBucketRateLimiter(1, 1, Clock.systemUTC()),
                new ClientIpResolver(TestProperties.defaults()), JsonMapper.builder().build()).getOrder();
    }
}
