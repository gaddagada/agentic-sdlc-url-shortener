package com.agenticsdlc.orchestrator.app.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.agenticsdlc.orchestrator.core.agent.llm.LlmRequest;
import com.agenticsdlc.orchestrator.core.agent.llm.LlmResponse;

/**
 * {@link ProviderCatalog}: provider listing, default resolution and refusal of unusable providers.
 */
class ProviderCatalogTest {

    private static final LlmProperties PROPERTIES = new LlmProperties(
            new LlmProperties.Anthropic(true, "claude-opus-5-5", "", 16000, Duration.ofSeconds(180)),
            new LlmProperties.Ollama(false, "http://localhost:11434", "qwen2.5-coder:7b", Duration.ofSeconds(240),
                    16384, 8192));

    @Test
    void listsDeterministicRegisteredAndDisabledProviders() {
        ProviderCatalog catalog = new ProviderCatalog(List.of(new StubClient("anthropic", false,
                "ANTHROPIC_API_KEY not set")), PROPERTIES, "deterministic");

        assertThat(catalog.providers()).extracting(ProviderStatus::id, ProviderStatus::available,
                        ProviderStatus::detail)
                .containsExactly(
                        tuple("deterministic", true,
                                "built-in deterministic agents (always available; also the fallback of every stage)"),
                        tuple("anthropic", false, "ANTHROPIC_API_KEY not set"),
                        tuple("ollama", false,
                                "disabled by configuration (sdlc.llm.ollama.enabled=false)"));
    }

    @Test
    void resolvesTheDefaultAndNormalizesIds() {
        ProviderCatalog catalog = new ProviderCatalog(List.of(new StubClient("ollama", true, "reachable")),
                PROPERTIES, "Ollama");

        assertThat(catalog.defaultProvider()).isEqualTo("ollama");
        assertThat(catalog.resolveAvailable(null)).isEqualTo("ollama");
        assertThat(catalog.resolveAvailable(" ")).isEqualTo("ollama");
        assertThat(catalog.resolveAvailable("DETERMINISTIC")).isEqualTo("deterministic");
    }

    @Test
    void refusesUnknownDisabledAndUnavailableProviders() {
        ProviderCatalog catalog = new ProviderCatalog(List.of(new StubClient("anthropic", false,
                "ANTHROPIC_API_KEY not set")), PROPERTIES, "deterministic");

        assertThatThrownBy(() -> catalog.resolveAvailable("anthropic")).isInstanceOf(ProviderUnavailableException.class)
                .hasMessageContaining("ANTHROPIC_API_KEY not set");
        assertThatThrownBy(() -> catalog.resolveAvailable("ollama")).isInstanceOf(ProviderUnavailableException.class)
                .hasMessageContaining("disabled");
        assertThatThrownBy(() -> catalog.resolveAvailable("gpt")).isInstanceOf(ProviderUnavailableException.class)
                .hasMessageContaining("unknown provider");
    }

    @Test
    void anUnknownDefaultProviderFailsFast() {
        assertThatThrownBy(() -> new ProviderCatalog(List.of(), PROPERTIES, "anthropic"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("default-provider");
    }

    private record StubClient(String providerId, boolean available, String detail) implements ProviderClient {

        @Override
        public String model() {
            return providerId + "-model";
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public int maxOutputTokens() {
            return 1000;
        }

        @Override
        public Duration requestTimeout() {
            return Duration.ofSeconds(10);
        }

        @Override
        public <T> LlmResponse<T> complete(LlmRequest request, Class<T> outputType) {
            throw new UnsupportedOperationException();
        }
    }
}
