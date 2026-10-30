package com.agenticsdlc.orchestrator.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.agenticsdlc.orchestrator.core.agent.AgentSettings;
import com.agenticsdlc.orchestrator.core.audit.HmacChangeRecordSigner;
import com.agenticsdlc.orchestrator.core.audit.UnsignedChangeRecordSigner;
import com.agenticsdlc.orchestrator.core.port.InMemoryRunLeaseProvider;

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

    @Test
    void signerLeaseAndRegistrySettingsHaveDefaultsAndAreValidated() {
        EngineConfig defaults = EngineConfig.defaults();
        assertThat(defaults.changeRecordSigner()).isInstanceOf(UnsignedChangeRecordSigner.class);
        assertThat(defaults.nodeId()).isEqualTo(EngineConfig.defaultNodeId())
                .matches("[A-Za-z0-9._-]+-" + ProcessHandle.current().pid() + "-[0-9a-f]{6}")
                .hasSizeLessThanOrEqualTo(EngineConfig.MAX_NODE_ID_LENGTH);
        assertThat(EngineConfig.defaultNodeId()).as("computed once per JVM").isSameAs(defaults.nodeId());
        assertThat(defaults.leaseProvider()).isInstanceOf(InMemoryRunLeaseProvider.class);
        assertThat(EngineConfig.defaults().leaseProvider()).as("each default config coordinates only its engine")
                .isNotSameAs(defaults.leaseProvider());
        assertThat(defaults.leaseTtl()).isEqualTo(Duration.ofSeconds(60));
        assertThat(defaults.registryRetention()).isEqualTo(Duration.ofHours(1));
        assertThat(defaults.registryMaxTerminalRuns()).isEqualTo(500);

        InMemoryRunLeaseProvider shared = new InMemoryRunLeaseProvider();
        HmacChangeRecordSigner signer = new HmacChangeRecordSigner(new byte[32], "k1");
        EngineConfig custom = EngineConfig.builder().changeRecordSigner(signer).nodeId("node-a").leaseProvider(shared)
                .leaseTtl(Duration.ofSeconds(5)).registryRetention(Duration.ZERO).registryMaxTerminalRuns(3).build();
        assertThat(custom.toBuilder().build()).isEqualTo(custom);
        assertThat(custom.changeRecordSigner()).isSameAs(signer);
        assertThat(custom.nodeId()).isEqualTo("node-a");
        assertThat(custom.leaseProvider()).isSameAs(shared);
        assertThat(custom.leaseTtl()).isEqualTo(Duration.ofSeconds(5));
        assertThat(custom.registryRetention()).isZero();
        assertThat(custom.registryMaxTerminalRuns()).isEqualTo(3);

        EngineConfig previousCanonical = new EngineConfig(0.5, 1.0, Duration.ofSeconds(30), false, false,
                Path.of("sandbox"), null, Duration.ofMinutes(10), Clock.systemUTC(), () -> null, List.of(), null, null,
                null, "mvn verify", 2.0, 100, 1000, EngineConfig.ProviderCircuitBreaker.DISABLED);
        assertThat(previousCanonical.sourceContextTotalChars()).isEqualTo(1000);
        assertThat(previousCanonical.changeRecordSigner()).isSameAs(UnsignedChangeRecordSigner.INSTANCE);
        assertThat(previousCanonical.nodeId()).isEqualTo(EngineConfig.defaultNodeId());
        assertThat(previousCanonical.leaseProvider()).isInstanceOf(InMemoryRunLeaseProvider.class);
        assertThat(previousCanonical.leaseTtl()).isEqualTo(EngineConfig.DEFAULT_LEASE_TTL);
        assertThat(previousCanonical.registryMaxTerminalRuns())
                .isEqualTo(EngineConfig.DEFAULT_REGISTRY_MAX_TERMINAL_RUNS);

        assertThatThrownBy(() -> EngineConfig.builder().nodeId(" ").build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EngineConfig.builder().nodeId("n".repeat(129)).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EngineConfig.builder().leaseTtl(Duration.ZERO).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EngineConfig.builder().registryRetention(Duration.ofSeconds(-1)).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EngineConfig.builder().registryMaxTerminalRuns(-1).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(EngineConfig.builder().nodeId(null).build().nodeId()).isEqualTo(EngineConfig.defaultNodeId());
    }
}
