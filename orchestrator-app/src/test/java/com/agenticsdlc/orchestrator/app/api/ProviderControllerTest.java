package com.agenticsdlc.orchestrator.app.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.agenticsdlc.orchestrator.app.llm.ProviderCatalog;
import com.agenticsdlc.orchestrator.app.llm.ProviderStatus;
import com.agenticsdlc.orchestrator.app.security.SecuritySliceConfiguration;
import com.agenticsdlc.orchestrator.core.engine.OrchestrationEngine;
import com.agenticsdlc.orchestrator.core.engine.ProviderCircuit;

/**
 * Web slice of {@link ProviderController}: availability from the catalog and the engine's circuit-breaker state per
 * LLM provider, with the failure text redacted and truncated.
 */
@WebMvcTest(ProviderController.class)
@Import(SecuritySliceConfiguration.class)
class ProviderControllerTest {

    private static final Instant OPENED = Instant.parse("2026-10-30T10:00:00Z");
    private static final Instant RETRY = Instant.parse("2026-10-30T10:02:00Z");

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private OrchestrationEngine engine;

    @MockitoBean
    private ProviderCatalog providers;

    @Test
    void listsTheCircuitOfEveryRegisteredLlmProvider() {
        when(providers.providers()).thenReturn(List.of(
                new ProviderStatus("deterministic", true, "rule-based", "built-in"),
                new ProviderStatus("anthropic", true, "claude-opus-5-5", "API key configured"),
                new ProviderStatus("ollama", false, "qwen2.5-coder:7b", "disabled by configuration")));
        String failure = "PROVIDER_ERROR: anthropic rejected the request (HTTP 401) key sk-ant-abcdefghijklmnopqrstuv "
                + "x".repeat(400);
        when(engine.providerCircuits()).thenReturn(Map.of("anthropic", new ProviderCircuit("anthropic",
                ProviderCircuit.State.OPEN, 3, OPENED, RETRY, failure)));

        MvcTestResult result = mvc.get().uri("/api/v1/providers").exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$[*].id").asArray()
                .containsExactly("deterministic", "anthropic", "ollama");
        assertThat(result).bodyJson().extractingPath("$[0].circuit").isNull();
        assertThat(result).bodyJson().extractingPath("$[2].circuit").isNull();
        assertThat(result).bodyJson().extractingPath("$[1].circuit.providerId").isEqualTo("anthropic");
        assertThat(result).bodyJson().extractingPath("$[1].circuit.state").isEqualTo("OPEN");
        assertThat(result).bodyJson().extractingPath("$[1].circuit.consecutiveFailures").isEqualTo(3);
        assertThat(result).bodyJson().extractingPath("$[1].circuit.openedAt").isEqualTo("2026-10-30T10:00:00Z");
        assertThat(result).bodyJson().extractingPath("$[1].circuit.retryAt").isEqualTo("2026-10-30T10:02:00Z");
        assertThat(result).bodyJson().extractingPath("$[1].circuit.lastFailure").asString()
                .startsWith("PROVIDER_ERROR: anthropic rejected the request (HTTP 401) key [redacted]")
                .doesNotContain("sk-ant-").hasSize(200);
    }

    @Test
    void aClosedCircuitHasNoTimestamps() {
        when(providers.providers()).thenReturn(List.of(
                new ProviderStatus("ollama", true, "qwen2.5-coder:7b", "reachable")));
        when(engine.providerCircuits()).thenReturn(Map.of("ollama", new ProviderCircuit("ollama",
                ProviderCircuit.State.CLOSED, 0, null, null, null)));

        MvcTestResult result = mvc.get().uri("/api/v1/providers").exchange();

        assertThat(result).bodyJson().extractingPath("$[0].circuit.state").isEqualTo("CLOSED");
        assertThat(result).bodyJson().extractingPath("$[0].circuit.retryAt").isNull();
        assertThat(result).bodyJson().extractingPath("$[0].circuit.lastFailure").isNull();
    }
}
