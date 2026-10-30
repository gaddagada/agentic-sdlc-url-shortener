package com.agenticsdlc.orchestrator.app.config;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import com.agenticsdlc.orchestrator.app.persistence.JdbcRunLeaseProvider;
import com.agenticsdlc.orchestrator.core.agent.AgentRegistry;
import com.agenticsdlc.orchestrator.core.audit.AuditSink;
import com.agenticsdlc.orchestrator.core.audit.ChangeRecordSigner;
import com.agenticsdlc.orchestrator.core.audit.HmacChangeRecordSigner;
import com.agenticsdlc.orchestrator.core.audit.UnsignedChangeRecordSigner;
import com.agenticsdlc.orchestrator.core.engine.EngineConfig;
import com.agenticsdlc.orchestrator.core.engine.OrchestrationEngine;
import com.agenticsdlc.orchestrator.core.graph.WorkflowDefinitions;
import com.agenticsdlc.orchestrator.core.graph.WorkflowGraph;
import com.agenticsdlc.orchestrator.core.json.JsonCodec;
import com.agenticsdlc.orchestrator.core.metrics.MetricsRecorder;
import com.agenticsdlc.orchestrator.core.policy.PolicyEngine;
import com.agenticsdlc.orchestrator.core.port.InMemoryRunLeaseProvider;
import com.agenticsdlc.orchestrator.core.port.RunLeaseProvider;
import com.agenticsdlc.orchestrator.core.port.RunStore;
import com.agenticsdlc.orchestrator.core.workspace.CodebaseIndexer;

/**
 * Wires the framework-independent engine: workflow graph, policy engine, JSON codec, workspace index, the signer of
 * release change records, the run lease provider, the {@link EngineConfig} and the {@link OrchestrationEngine}
 * itself (closed on shutdown so in-flight attempts are cancelled and the node's run leases are released).
 *
 * <p>The {@link JsonCodec} owns its own canonical Jackson 3 mapper (sorted properties, ISO-8601 dates); it is used
 * for persistence snapshots, hashing and LLM output parsing. HTTP bodies use the mapper auto-configured by
 * Spring Boot.</p>
 */
@Configuration(proxyBeanMethods = false)
public class EngineConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(EngineConfiguration.class);

    /**
     * Returns the shared canonical JSON codec.
     *
     * @return the codec
     */
    @Bean
    JsonCodec jsonCodec() {
        return JsonCodec.standard();
    }

    /**
     * Returns the default SDLC workflow graph.
     *
     * @return the graph
     */
    @Bean
    WorkflowGraph workflowGraph() {
        return new WorkflowGraph(WorkflowDefinitions.defaultSdlc());
    }

    /**
     * Returns the policy engine with the default rule set.
     *
     * @return the policy engine
     */
    @Bean
    PolicyEngine policyEngine() {
        return PolicyEngine.withDefaultRules();
    }

    /**
     * Returns the workspace index holder (indexed at startup by {@code WorkspaceIndexWarmup}).
     *
     * @param properties configuration
     * @param clock clock
     * @return the holder
     */
    @Bean
    WorkspaceIndexHolder workspaceIndexHolder(OrchestratorProperties properties, Clock clock) {
        return new WorkspaceIndexHolder(properties.workspace().resolvedRoot(), properties.workspace().enabled(),
                new CodebaseIndexer(clock));
    }

    /**
     * Returns the signer of finalized release change records: HMAC-SHA256 with {@code sdlc.governance.signing-key}
     * when it is set, otherwise the unsigned signer (no signature is attached). The key is never logged.
     *
     * @param properties configuration
     * @return the signer
     * @throws IllegalStateException when the configured key is malformed or shorter than 32 bytes
     */
    @Bean
    ChangeRecordSigner changeRecordSigner(OrchestratorProperties properties) {
        OrchestratorProperties.Governance governance = properties.governance();
        if (!governance.hasSigningKey()) {
            LOG.info("release change records are not signed (sdlc.governance.signing-key is not set)");
            return UnsignedChangeRecordSigner.INSTANCE;
        }
        byte[] key = governance.signingKeyBytes();
        try {
            HmacChangeRecordSigner signer = new HmacChangeRecordSigner(key, governance.signingKeyId());
            LOG.info("release change records are signed algorithm={} keyId={}", signer.algorithm(), signer.keyId());
            return signer;
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /**
     * Returns the run lease provider: the {@code run_leases} table ({@code sdlc.engine.lease-provider=jdbc}, the
     * default, shared by every node of the database) or an in-memory provider ({@code memory}, single node).
     *
     * @param properties configuration
     * @param clock the engine clock (judges expiry)
     * @param dataSource the control plane database (jdbc only)
     * @param transactionManager its transaction manager (jdbc only)
     * @return the provider
     */
    @Bean
    RunLeaseProvider runLeaseProvider(OrchestratorProperties properties, Clock clock,
                                      ObjectProvider<DataSource> dataSource,
                                      ObjectProvider<PlatformTransactionManager> transactionManager) {
        return switch (properties.engine().leaseProvider()) {
            case JDBC -> new JdbcRunLeaseProvider(dataSource.getObject(), transactionManager.getObject(), clock);
            case MEMORY -> new InMemoryRunLeaseProvider(clock);
        };
    }

    /**
     * Builds the engine configuration from {@code sdlc.engine.*} and the wired collaborators.
     *
     * @param properties configuration
     * @param workspace workspace index holder
     * @param clock clock
     * @param signer change record signer
     * @param leases run lease provider
     * @return the configuration
     * @throws IllegalStateException when the lease TTL does not outlast the longest retry backoff
     */
    @Bean
    EngineConfig engineConfig(OrchestratorProperties properties, WorkspaceIndexHolder workspace, Clock clock,
                              ChangeRecordSigner signer, RunLeaseProvider leases) {
        OrchestratorProperties.Engine engine = properties.engine();
        EngineConfig config = EngineConfig.builder()
                .ambiguityThreshold(engine.ambiguityThreshold())
                .backoffScale(engine.backoffScale())
                .sandboxEnabled(engine.sandboxEnabled())
                .verifyBuildEnabled(engine.verifyBuildEnabled())
                .sandboxRoot(engine.sandboxRoot())
                .llmTimeoutScale(engine.llmTimeoutScale())
                .buildCommand(engine.buildCommand())
                .workspaceRoot(workspace.root())
                .clock(clock)
                .workspaceIndex(workspace::current)
                .changeRecordSigner(signer)
                .nodeId(engine.resolvedNodeId())
                .leaseProvider(leases)
                .leaseTtl(engine.leaseTtl())
                .registryRetention(engine.registryRetention())
                .registryMaxTerminalRuns(engine.registryMaxTerminalRuns())
                .build();
        requireLeaseOutlastsBackoff(config);
        LOG.info("engine node={} leaseProvider={} leaseTtl={} registryRetention={} registryMaxTerminalRuns={}",
                config.nodeId(), engine.leaseProvider().name().toLowerCase(Locale.ROOT), config.leaseTtl(),

                config.registryRetention(), config.registryMaxTerminalRuns());
        return config;
    }

    /**
     * Fails unless the lease TTL is longer than the longest retry backoff ({@link EngineConfig#maxBackoff()} times
     * the backoff scale): nothing renews a lease while a retry waits, so a shorter TTL would let another node take a
     * run over between two attempts.
     *
     * @param config the engine configuration
     * @throws IllegalStateException when {@code leaseTtl <= maxBackoff x backoffScale}
     */
    static void requireLeaseOutlastsBackoff(EngineConfig config) {
        double scaledNanos = config.maxBackoff().toNanos() * config.backoffScale();
        Duration longest = scaledNanos >= Long.MAX_VALUE ? Duration.ofNanos(Long.MAX_VALUE)
                : Duration.ofNanos(Math.round(scaledNanos));
        if (config.leaseTtl().compareTo(longest) <= 0) {
            throw new IllegalStateException("sdlc.engine.lease-ttl (" + config.leaseTtl().toSeconds() + " s) must be "
                    + "longer than the longest retry backoff (engine max backoff " + config.maxBackoff().toSeconds()
                    + " s x sdlc.engine.backoff-scale " + config.backoffScale() + " = " + longest.toSeconds()
                    + " s): nothing renews a run lease while a retry waits, so another node could take the run over");
        }
    }

    /**
     * Creates the orchestration engine.
     *
     * @param graph workflow graph
     * @param agents agent registry
     * @param policyEngine policy engine
     * @param runStore run store (JPA)
     * @param auditSink audit sink (JPA)
     * @param metrics metrics recorder (Micrometer)
     * @param config engine configuration
     * @return the engine
     */
    @Bean(destroyMethod = "close")
    OrchestrationEngine orchestrationEngine(WorkflowGraph graph, AgentRegistry agents, PolicyEngine policyEngine,
                                            RunStore runStore, AuditSink auditSink, MetricsRecorder metrics,
                                            EngineConfig config) {
        return new OrchestrationEngine(graph, agents, policyEngine, runStore, auditSink, metrics, config);
    }
}
