package com.agenticsdlc.orchestrator.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.agenticsdlc.orchestrator.core.agent.AgentSettings;

class EngineConfigTest {

    @Test
    void sandboxBuildsUseAFixedEngineConfiguredCommand() {
        EngineConfig defaults = EngineConfig.defaults();
        assertThat(defaults.buildCommand()).isEqualTo("./mvnw -B -ntp -q verify")
                .isEqualTo(AgentSettings.DEFAULT_BUILD_COMMAND);
        assertThat(defaults.agentSettings().buildCommand()).isEqualTo("./mvnw -B -ntp -q verify");

        EngineConfig custom = EngineConfig.builder().buildCommand("mvn -q -pl url-shortener verify").build();
        assertThat(custom.agentSettings().buildCommand()).isEqualTo("mvn -q -pl url-shortener verify");
        assertThat(custom.toBuilder().build().buildCommand()).isEqualTo("mvn -q -pl url-shortener verify");
        assertThatThrownBy(() -> EngineConfig.builder().buildCommand(" ").build())
                .isInstanceOf(IllegalArgumentException.class);

        EngineConfig legacy = new EngineConfig(0.5, 1.0, Duration.ofSeconds(30), false, false, Path.of("sandbox"),
                null, Duration.ofMinutes(10), Clock.systemUTC(), () -> null, List.of(), null, null, null);
        assertThat(legacy.buildCommand()).as("pre-existing constructor keeps working")
                .isEqualTo(AgentSettings.DEFAULT_BUILD_COMMAND);
        assertThat(legacy.llmTimeoutScale()).isEqualTo(EngineConfig.DEFAULT_LLM_TIMEOUT_SCALE);
        assertThat(new AgentSettings(0.5, false, false, null, Path.of("sandbox"), Duration.ofMinutes(1))
                .buildCommand()).isEqualTo(AgentSettings.DEFAULT_BUILD_COMMAND);
    }

    @Test
    void sourceContextLimitsAndTheProviderCircuitBreakerHaveDefaultsAndAreValidated() {
        EngineConfig defaults = EngineConfig.defaults();
        assertThat(defaults.sourceContextPerFileChars()).isEqualTo(20_000)
                .isEqualTo(EngineConfig.DEFAULT_SOURCE_CONTEXT_PER_FILE_CHARS);
        assertThat(defaults.sourceContextTotalChars()).isEqualTo(120_000)
                .isEqualTo(EngineConfig.DEFAULT_SOURCE_CONTEXT_TOTAL_CHARS);
        assertThat(defaults.providerCircuitBreaker()).isEqualTo(EngineConfig.ProviderCircuitBreaker.DEFAULTS);
        assertThat(defaults.providerCircuitBreaker().failureThreshold()).isEqualTo(3);
        assertThat(defaults.providerCircuitBreaker().cooldown()).isEqualTo(Duration.ofMinutes(10));
        assertThat(defaults.providerCircuitBreaker().enabled()).isTrue();
        assertThat(EngineConfig.ProviderCircuitBreaker.DISABLED.enabled()).isFalse();

        EngineConfig custom = EngineConfig.builder().sourceContextPerFileChars(500).sourceContextTotalChars(2_000)
                .providerCircuitBreaker(new EngineConfig.ProviderCircuitBreaker(5, Duration.ofMinutes(1))).build();
        EngineConfig copy = custom.toBuilder().build();
        assertThat(copy.sourceContextPerFileChars()).isEqualTo(500);
        assertThat(copy.sourceContextTotalChars()).isEqualTo(2_000);
        assertThat(copy.providerCircuitBreaker().failureThreshold()).isEqualTo(5);

        EngineConfig previousCanonical = new EngineConfig(0.5, 1.0, Duration.ofSeconds(30), false, false,
                Path.of("sandbox"), null, Duration.ofMinutes(10), Clock.systemUTC(), () -> null, List.of(), null, null,
                null, "mvn verify", 2.0);
        assertThat(previousCanonical.sourceContextPerFileChars()).isEqualTo(20_000);
        assertThat(previousCanonical.providerCircuitBreaker()).isEqualTo(EngineConfig.ProviderCircuitBreaker.DEFAULTS);

        assertThatThrownBy(() -> EngineConfig.builder().sourceContextPerFileChars(0).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EngineConfig.builder().sourceContextTotalChars(-1).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EngineConfig.ProviderCircuitBreaker(-1, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EngineConfig.ProviderCircuitBreaker(3, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
