package com.agenticsdlc.orchestrator.app.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * {@link SecurityHeadersFilter}: the defensive headers are set on every response, and the console's
 * {@code index.html} is compatible with the Content Security Policy (no inline scripts or inline event handlers).
 */
class SecurityHeadersFilterTest {

    private static final Path INDEX = Path.of("src", "main", "resources", "static", "index.html");

    @Test
    void setsTheSecurityHeadersBeforeTheChainRuns() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new SecurityHeadersFilter().doFilter(new MockHttpServletRequest("GET", "/api/v1/runs"), response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(response.getHeader("Content-Security-Policy")).isEqualTo("default-src 'self'; "
                + "img-src 'self' data:; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'");
    }

    @Test
    void theConsoleHasNoInlineScriptsOrInlineEventHandlers() throws Exception {
        String html = Files.readString(INDEX, StandardCharsets.UTF_8);

        assertThat(Pattern.compile("<script(?![^>]*\\bsrc=)[^>]*>", Pattern.CASE_INSENSITIVE).matcher(html).find())
                .as("inline <script> block").isFalse();
        assertThat(Pattern.compile("\\son[a-z]+\\s*=", Pattern.CASE_INSENSITIVE).matcher(html).find())
                .as("inline event handler attribute").isFalse();
        assertThat(html).doesNotContainIgnoringCase("javascript:");
    }
}
