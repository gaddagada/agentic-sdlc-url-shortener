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
}
