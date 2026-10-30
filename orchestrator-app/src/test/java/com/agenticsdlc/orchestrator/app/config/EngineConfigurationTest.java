package com.agenticsdlc.orchestrator.app.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.agenticsdlc.orchestrator.app.LogCapture;
import com.agenticsdlc.orchestrator.app.llm.LlmProperties;
import com.agenticsdlc.orchestrator.core.agent.AgentRegistry;
import com.agenticsdlc.orchestrator.core.agent.deterministic.DeterministicAgents;
import com.agenticsdlc.orchestrator.core.audit.ChangeRecordSigner;
import com.agenticsdlc.orchestrator.core.audit.HmacChangeRecordSigner;
import com.agenticsdlc.orchestrator.core.audit.InMemoryAuditSink;
import com.agenticsdlc.orchestrator.core.audit.UnsignedChangeRecordSigner;
import com.agenticsdlc.orchestrator.core.engine.EngineConfig;
import com.agenticsdlc.orchestrator.core.engine.OrchestrationEngine;
import com.agenticsdlc.orchestrator.core.metrics.LlmPrice;
import com.agenticsdlc.orchestrator.core.metrics.LlmPriceBook;
import com.agenticsdlc.orchestrator.core.metrics.MetricsRecorder;
import com.agenticsdlc.orchestrator.core.metrics.NoopMetricsRecorder;
import com.agenticsdlc.orchestrator.core.policy.PolicyEngine;
import com.agenticsdlc.orchestrator.core.policy.pack.PolicyPackLoader;
import com.agenticsdlc.orchestrator.core.policy.rules.DefaultPolicyRules;
import com.agenticsdlc.orchestrator.core.port.InMemoryRunLeaseProvider;
import com.agenticsdlc.orchestrator.core.port.InMemoryRunStore;
import com.agenticsdlc.orchestrator.core.port.RunLeaseProvider;

import ch.qos.logback.classic.Level;

/**
 * {@link EngineConfiguration} in a minimal context (in-memory store, audit sink and leases): the change record signer
 * follows {@code sdlc.governance.signing-key} (base64 or raw text, never logged, a short or malformed key fails
 * startup with a clear message), every {@code sdlc.engine.*} lease and registry setting reaches the
 * {@link EngineConfig}, and a lease TTL that does not outlast the longest retry backoff fails startup.
 */
class EngineConfigurationTest {

    private static final String KEY_TEXT = "a-local-signing-key-of-forty-bytes-length";
    private static final String BASE64_KEY = "base64:" + Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Wiring.class)
            .withBean(AgentRegistry.class, () -> AgentRegistry.of(DeterministicAgents.all(null)))
            .withBean(InMemoryRunStore.class, InMemoryRunStore::new)
            .withBean(InMemoryAuditSink.class, InMemoryAuditSink::new)
            .withBean(MetricsRecorder.class, () -> NoopMetricsRecorder.INSTANCE)
            .withPropertyValues("sdlc.workspace.enabled=false", "sdlc.engine.lease-provider=memory");

    @Test
    void withoutASigningKeyChangeRecordsAreUnsigned() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ChangeRecordSigner.class)).isSameAs(UnsignedChangeRecordSigner.INSTANCE);
            assertThat(context.getBean(EngineConfig.class).changeRecordSigner())
                    .isSameAs(UnsignedChangeRecordSigner.INSTANCE);
        });
        runner.withPropertyValues("sdlc.governance.signing-key= ").run(context -> assertThat(
                context.getBean(ChangeRecordSigner.class)).isSameAs(UnsignedChangeRecordSigner.INSTANCE));
    }

    @Test
    void aBase64KeyWiresTheHmacSignerWithItsKeyIdAndIsNeverLogged() {
        LogCapture log = LogCapture.of(EngineConfiguration.class, Level.DEBUG);
        try (log) {
            runner.withPropertyValues("sdlc.governance.signing-key=" + BASE64_KEY,
                    "sdlc.governance.signing-key-id=k-2026").run(context -> {
                        assertThat(context).hasNotFailed();
                        ChangeRecordSigner signer = context.getBean(ChangeRecordSigner.class);
                        assertThat(signer).isInstanceOf(HmacChangeRecordSigner.class);
                        assertThat(signer.keyId()).isEqualTo("k-2026");
                        assertThat(context.getBean(EngineConfig.class).changeRecordSigner()).isSameAs(signer);
                        HmacChangeRecordSigner expected = new HmacChangeRecordSigner(
                                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8), "k-2026");
                        assertThat(signer.sign("payload")).isEqualTo(expected.sign("payload"));
                        assertThat(context.getBean(OrchestratorProperties.class).governance().toString())
                                .doesNotContain(BASE64_KEY.substring(7)).contains("<redacted>");
                    });
        }
        assertThat(log.lines()).contains("INFO release change records are signed algorithm=HmacSHA256 keyId=k-2026")
                .noneSatisfy(line -> assertThat(line).containsAnyOf(BASE64_KEY.substring(7), "0123456789abcdef"));
    }

    @Test
    void aRawTextKeyIsUsedAsItsUtf8BytesWithTheDefaultKeyId() {
        runner.withPropertyValues("sdlc.governance.signing-key=" + KEY_TEXT).run(context -> {
            ChangeRecordSigner signer = context.getBean(ChangeRecordSigner.class);
            assertThat(signer.keyId()).isEqualTo("local");
            assertThat(signer.sign("x")).isEqualTo(new HmacChangeRecordSigner(
                    KEY_TEXT.getBytes(StandardCharsets.UTF_8), "local").sign("x"));
        });
    }

    @Test
    void aShortOrMalformedKeyIsRejectedAtStartupWithoutRevealingIt() {
        runner.withPropertyValues("sdlc.governance.signing-key=too-short-secret").run(context ->
                assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("sdlc.governance.signing-key must decode to at least 32 bytes (got 16)")
                        .hasMessageNotContaining("too-short-secret"));
        runner.withPropertyValues("sdlc.governance.signing-key=base64:" + Base64.getEncoder()
                        .encodeToString(new byte[31]))
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("at least 32 bytes (got 31)"));
        runner.withPropertyValues("sdlc.governance.signing-key=base64:%%not base64%%").run(context ->
                assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("is not valid base64").hasMessageNotContaining("%%not"));
    }

    @Test
    void leaseAndRegistrySettingsReachTheEngineConfig() {
        runner.withPropertyValues("sdlc.engine.node-id=node-7", "sdlc.engine.lease-ttl=90s",
                        "sdlc.engine.registry-retention=15m", "sdlc.engine.registry-max-terminal-runs=42")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    EngineConfig config = context.getBean(EngineConfig.class);
                    assertThat(config.nodeId()).isEqualTo("node-7");
                    assertThat(config.leaseTtl()).isEqualTo(Duration.ofSeconds(90));
                    assertThat(config.registryRetention()).isEqualTo(Duration.ofMinutes(15));
                    assertThat(config.registryMaxTerminalRuns()).isEqualTo(42);
                    assertThat(config.leaseProvider()).isSameAs(context.getBean(RunLeaseProvider.class))
                            .isInstanceOf(InMemoryRunLeaseProvider.class);
                    assertThat(context.getBean(OrchestrationEngine.class)).isNotNull();
                });
    }

    @Test
    void theDefaultsAreTheEngineDefaults() {
        runner.run(context -> {
            EngineConfig config = context.getBean(EngineConfig.class);
            OrchestratorProperties.Engine engine = context.getBean(OrchestratorProperties.class).engine();
            assertThat(config.nodeId()).isEqualTo(EngineConfig.defaultNodeId());
            assertThat(config.leaseTtl()).isEqualTo(EngineConfig.DEFAULT_LEASE_TTL);
            assertThat(config.registryRetention()).isEqualTo(EngineConfig.DEFAULT_REGISTRY_RETENTION);
            assertThat(config.registryMaxTerminalRuns()).isEqualTo(EngineConfig.DEFAULT_REGISTRY_MAX_TERMINAL_RUNS);
            assertThat(engine.maintenanceInterval()).isEqualTo(Duration.ofSeconds(30));
            assertThat(engine.leaseProvider()).isEqualTo(OrchestratorProperties.Engine.LeaseProvider.MEMORY);
        });
        new ApplicationContextRunner().withUserConfiguration(PropertiesOnly.class).run(context -> assertThat(
                context.getBean(OrchestratorProperties.class).engine().leaseProvider())
                .as("the default provider").isEqualTo(OrchestratorProperties.Engine.LeaseProvider.JDBC));
    }

    @Test
    void aLeaseTtlThatDoesNotOutlastTheLongestRetryBackoffFailsStartup() {
        runner.withPropertyValues("sdlc.engine.lease-ttl=30s").run(context ->
                assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("sdlc.engine.lease-ttl (30 s) must be longer than the longest retry "
                                + "backoff (engine max backoff 30 s x sdlc.engine.backoff-scale 1.0 = 30 s)"));
        runner.withPropertyValues("sdlc.engine.backoff-scale=3", "sdlc.engine.lease-ttl=60s").run(context ->
                assertThat(context).hasFailed().getFailure().rootCause().hasMessageContaining("= 90 s"));
        runner.withPropertyValues("sdlc.engine.backoff-scale=3", "sdlc.engine.lease-ttl=91s")
                .run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues("sdlc.engine.backoff-scale=0", "sdlc.engine.lease-ttl=1s")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void invalidEngineSettingsAreRejectedWhileBinding() {
        runner.withPropertyValues("sdlc.engine.lease-ttl=0s").run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().hasMessageContaining("sdlc.engine.lease-ttl must be positive"));
        runner.withPropertyValues("sdlc.engine.maintenance-interval=-1s").run(context -> assertThat(context)
                .hasFailed().getFailure().rootCause().hasMessageContaining("maintenance-interval must be positive"));
        runner.withPropertyValues("sdlc.engine.node-id=node with spaces").run(context -> assertThat(context)
                .hasFailed());
        runner.withPropertyValues("sdlc.engine.registry-max-terminal-runs=-1").run(context -> assertThat(context)
                .hasFailed());
    }

    @Test
    void pricesTheDependencyScanAndThePolicyPackReachTheEngine() {
        runner.withPropertyValues(
                        "sdlc.llm.pricing.providers.anthropic.models.claude-opus-5.input-per-million=5",
                        "sdlc.llm.pricing.providers.anthropic.models.claude-opus-5.output-per-million=25",
                        "sdlc.llm.pricing.providers.ollama.default.input-per-million=0",
                        "sdlc.llm.pricing.providers.ollama.default.output-per-million=0",
                        "sdlc.engine.dependency-scan-command=./mvnw -B -ntp -q dependency:tree",
                        "sdlc.engine.dependency-scan-timeout=2m",
                        "sdlc.policy.pack=classpath:policy/example-policy-pack.yaml")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    EngineConfig config = context.getBean(EngineConfig.class);
                    assertThat(config.priceBook()).isSameAs(context.getBean(LlmPriceBook.class));
                    assertThat(config.priceBook().lookup("anthropic", "claude-opus-5-5"))
                            .contains(new LlmPrice(5, 25, 5, 5));
                    assertThat(config.priceBook().lookup("ollama", "qwen2.5-coder:7b")).contains(LlmPrice.flat(0, 0));
                    assertThat(config.dependencyScanCommand()).isEqualTo("./mvnw -B -ntp -q dependency:tree");
                    assertThat(config.dependencyScanTimeout()).isEqualTo(Duration.ofMinutes(2));
                    PolicyEngine policy = context.getBean(PolicyEngine.class);
                    assertThat(policy.pack()).isEqualTo(PolicyPackLoader.sample());
                    assertThat(context.getBean(OrchestrationEngine.class).policyPack()).isEqualTo(policy.pack());
                });
    }

    @Test
    void withoutConfigurationThereAreNoPricesNoScanAndOnlyTheBuiltInRules() {
        runner.withPropertyValues("sdlc.engine.dependency-scan-command=  ").run(context -> {
            EngineConfig config = context.getBean(EngineConfig.class);
            assertThat(config.priceBook()).isSameAs(LlmPriceBook.EMPTY);
            assertThat(config.dependencyScanCommand()).isNull();
            assertThat(config.dependencyScanTimeout()).isEqualTo(Duration.ofMinutes(5));
            assertThat(context.getBean(PolicyEngine.class).pack()).isNull();
            assertThat(context.getBean(PolicyEngine.class).rules()).hasSize(DefaultPolicyRules.all().size());
        });
    }

    @Test
    void anInvalidPolicyPackFailsStartupListingEveryProblem(@TempDir Path dir) throws IOException {
        Path pack = dir.resolve("broken.yaml");
        Files.writeString(pack, """
                name: broken
                version: "1"
                disabledRules: [CHG-002]
                rules:
                  - id: bad
                    phase: RUN_START
                    when: {type: intentMatches, regexes: ["(unclosed"]}
                    effect: WARN
                    message: never loads
                """, StandardCharsets.UTF_8);

        runner.withPropertyValues("sdlc.policy.pack=" + pack).run(context -> assertThat(context).hasFailed()
                .getFailure().hasStackTraceContaining("is not a valid policy pack (3 problems)")
                .hasStackTraceContaining("  - $.disabledRules[0]").hasStackTraceContaining("  - $.rules[0].id")
                .hasStackTraceContaining("  - $.rules[0].when.regexes[0]"));
        runner.withPropertyValues("sdlc.policy.pack=" + dir.resolve("absent.json")).run(context ->
                assertThat(context).hasFailed().getFailure().rootCause().hasMessageContaining("file not found"));
    }

    @Test
    void invalidScanTimeoutsAndPricesAreRejectedWhileBinding() {
        runner.withPropertyValues("sdlc.engine.dependency-scan-timeout=0s").run(context -> assertThat(context)
                .hasFailed().getFailure().rootCause().hasMessageContaining("dependency-scan-timeout must be positive"));
        runner.withPropertyValues("sdlc.llm.pricing.providers.anthropic.default.input-per-million=-1",
                        "sdlc.llm.pricing.providers.anthropic.default.output-per-million=15")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("inputPerMillion"));
        runner.withPropertyValues("sdlc.llm.pricing.providers.anthropic.models.claude-opus-5.input-per-million=5")
                .run(context -> assertThat(context).as("an output price is required").hasFailed().getFailure()
                        .hasStackTraceContaining("outputPerMillion"));
    }

    /**
     * The engine wiring with its properties and clock.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({OrchestratorProperties.class, LlmProperties.class})
    @Import({EngineConfiguration.class, ClockConfig.class})
    static class Wiring {
    }

    /**
     * The bound properties only.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OrchestratorProperties.class)
    static class PropertiesOnly {
    }
}
