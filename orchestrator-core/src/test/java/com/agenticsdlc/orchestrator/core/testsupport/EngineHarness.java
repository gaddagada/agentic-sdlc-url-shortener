package com.agenticsdlc.orchestrator.core.testsupport;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import com.agenticsdlc.orchestrator.core.agent.AgentRegistry;
import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.agent.StageAgent;
import com.agenticsdlc.orchestrator.core.audit.Actor;
import com.agenticsdlc.orchestrator.core.audit.AuditEvent;
import com.agenticsdlc.orchestrator.core.audit.AuditEventType;
import com.agenticsdlc.orchestrator.core.audit.InMemoryAuditSink;
import com.agenticsdlc.orchestrator.core.engine.CompensationHandler;
import com.agenticsdlc.orchestrator.core.engine.EngineConfig;
import com.agenticsdlc.orchestrator.core.engine.OrchestrationEngine;
import com.agenticsdlc.orchestrator.core.graph.WorkflowDefinition;
import com.agenticsdlc.orchestrator.core.graph.WorkflowDefinitions;
import com.agenticsdlc.orchestrator.core.graph.WorkflowGraph;
import com.agenticsdlc.orchestrator.core.metrics.MetricsRecorder;
import com.agenticsdlc.orchestrator.core.metrics.NoopMetricsRecorder;
import com.agenticsdlc.orchestrator.core.model.Compensation;
import com.agenticsdlc.orchestrator.core.model.Run;
import com.agenticsdlc.orchestrator.core.model.RunStatus;
import com.agenticsdlc.orchestrator.core.model.StageStatus;
import com.agenticsdlc.orchestrator.core.policy.PolicyEngine;
import com.agenticsdlc.orchestrator.core.port.InMemoryRunStore;

/**
 * Builds an engine with fake agents, in-memory ports and immediate retries, and offers Awaitility helpers.
 */
public final class EngineHarness implements AutoCloseable {

    public static final Duration TIMEOUT = Duration.ofSeconds(10);
    public static final Actor ALICE = Actor.human("alice");
    public static final String TEST_UNDO = "TEST_UNDO";

    public final InMemoryRunStore store;
    public final InMemoryAuditSink audit;
    public final List<Compensation> compensated = new CopyOnWriteArrayList<>();
    private final Map<AgentRole, StageAgent> agents = new EnumMap<>(AgentRole.class);
    private WorkflowDefinition definition = WorkflowDefinitions.defaultSdlc();
    private UnaryOperator<EngineConfig.Builder> configCustomizer = b -> b;
    private PolicyEngine policy = PolicyEngine.withDefaultRules();
    private MetricsRecorder metrics = NoopMetricsRecorder.INSTANCE;
    private final List<OrchestrationEngine> engines = new ArrayList<>();
    private OrchestrationEngine engine;

    public EngineHarness() {
        this(new InMemoryRunStore(), new InMemoryAuditSink());
    }

    public EngineHarness(InMemoryRunStore store, InMemoryAuditSink audit) {
        this.store = store;
        this.audit = audit;
        for (AgentRole role : AgentRole.values()) {
            agents.put(role, FakeAgent.succeeding(role));
        }
    }

    public EngineHarness agent(StageAgent agent) {
        agents.put(agent.role(), agent);
        return this;
    }

    public EngineHarness agents(List<StageAgent> list) {
        list.forEach(this::agent);
        return this;
    }

    public EngineHarness definition(UnaryOperator<WorkflowDefinition> change) {
        definition = change.apply(definition);
        return this;
    }

    public EngineHarness policy(PolicyEngine engineWithRules) {
        this.policy = engineWithRules;
        return this;
    }

    public EngineHarness metrics(MetricsRecorder recorder) {
        this.metrics = recorder;
        return this;
    }

    public EngineHarness config(UnaryOperator<EngineConfig.Builder> customizer) {
        UnaryOperator<EngineConfig.Builder> previous = configCustomizer;
        configCustomizer = b -> customizer.apply(previous.apply(b));
        return this;
    }

    public OrchestrationEngine start() {
        CompensationHandler handler = new CompensationHandler() {
            @Override
            public String action() {
                return TEST_UNDO;
            }

            @Override
            public void compensate(Compensation compensation) {
                compensated.add(compensation);
            }
        };
        EngineConfig config = configCustomizer.apply(EngineConfig.builder().backoffScale(0)
                .compensationHandler(handler)).build();
        engine = new OrchestrationEngine(new WorkflowGraph(definition), AgentRegistry.of(agents.values()), policy,
                store, audit, metrics, config);
        engines.add(engine);
        return engine;
    }

    public OrchestrationEngine engine() {
        return engine;
    }

    public Run awaitRun(String runId, Predicate<Run> condition) {
        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(10)).until(() -> condition.test(engine.get(runId)));
        return engine.get(runId);
    }

    public Run awaitStatus(String runId, RunStatus status) {
        return awaitRun(runId, r -> r.status() == status);
    }

    public Run awaitStage(String runId, String stageId, StageStatus status) {
        return awaitRun(runId, r -> r.stage(stageId).status() == status);
    }

    /** Awaits WAITING_HUMAN with exactly the given stage awaiting approval. */
    public Run awaitApproval(String runId, String stageId) {
        return awaitRun(runId, r -> r.status() == RunStatus.WAITING_HUMAN
                && r.stage(stageId).status() == StageStatus.AWAITING_APPROVAL);
    }

    public List<AuditEvent> events(String runId) {
        return audit.forRun(runId, 0);
    }

    public List<AuditEventType> eventTypes(String runId) {
        return events(runId).stream().map(AuditEvent::type).toList();
    }

    public void eachEngine(Consumer<OrchestrationEngine> action) {
        engines.forEach(action);
    }

    @Override
    public void close() {
        engines.forEach(OrchestrationEngine::close);
    }
}
