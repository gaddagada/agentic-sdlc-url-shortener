package com.agenticsdlc.orchestrator.app.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.agenticsdlc.orchestrator.app.events.RunCommitListener;
import com.agenticsdlc.orchestrator.app.events.RunCommitted;
import com.agenticsdlc.orchestrator.app.runs.RunListItem;
import com.agenticsdlc.orchestrator.core.audit.AuditEvent;
import com.agenticsdlc.orchestrator.core.audit.AuditEventType;
import com.agenticsdlc.orchestrator.core.audit.AuditSink;
import com.agenticsdlc.orchestrator.core.graph.WorkflowGraph;
import com.agenticsdlc.orchestrator.core.json.JsonCodec;
import com.agenticsdlc.orchestrator.core.model.Run;
import com.agenticsdlc.orchestrator.core.model.RunSnapshot;
import com.agenticsdlc.orchestrator.core.model.StageStatus;
import com.agenticsdlc.orchestrator.core.port.RunStore;

/**
 * JPA implementation of the engine's {@link RunStore}: the run aggregate is persisted as one JSON snapshot
 * (optimistic {@code @Version}), and artifact versions not yet persisted are inserted into the {@code artifacts}
 * table.
 *
 * <p>The engine calls {@link #commit(Run, List, AuditSink)} after every mutation, from its own (virtual) threads
 * while holding the run lock and outside any Spring-managed transaction, so every method demarcates its
 * transaction explicitly with a {@link TransactionTemplate} instead of relying on {@code @Transactional} proxies.
 * {@code commit} saves the snapshot, inserts new artifacts and appends the audit events of the mutation in
 * <em>one</em> transaction (the JPA audit sink joins it), so a failing audit insert also rolls back the snapshot and
 * the run state and its audit trail can never diverge. The snapshot is serialized synchronously before the
 * transaction starts, so the live run is never read after the method returns.</p>
 *
 * <p>Next to the snapshot, every save fills the listing columns of the {@code runs} row: autonomy, workspace mode,
 * the current risk level and the audit chain head from the run; {@code started_by} from the actor of the run's
 * RUN_CREATED event (or the {@value #CREATED_BY_TAG} run tag); {@code node_id} from the {@code nodeId} of the last
 * committed event that records one (RUN_CREATED, RUN_RESUMED, REPLAN, RUN_INTERRUPTED, STAGE_STARTED), that is the
 * engine node that last started work on the run; and the progress columns (stages waiting for a human, succeeded and
 * total stages) from the snapshot.</p>
 *
 * <p>After the transaction commits ({@link TransactionSynchronization#afterCommit()}), every save and commit hands a
 * {@link RunCommitted} (the run's list row as written, the committed snapshot and its audit events) to the
 * {@link RunCommitListener}, which feeds the console's server-sent events and the run observations. Nothing is
 * published for a transaction that rolls back.</p>
 */
@Component
public class JpaRunStore implements RunStore {

    /**
     * Run tag naming the operator who started the run; the control plane's run launcher writes it (the persistence
     * layer does not depend on the web layer, so the name is repeated here and pinned by a test).
     */
    static final String CREATED_BY_TAG = "createdBy";

    /** Audit data key of the engine node id. */
    static final String NODE_ID_KEY = "nodeId";

    private static final Logger LOG = LoggerFactory.getLogger(JpaRunStore.class);

    private final RunRepository runs;
    private final ArtifactRepository artifacts;
    private final JsonCodec codec;
    private final WorkflowGraph graph;
    private final TransactionTemplate transactions;
    private final TransactionTemplate readTransactions;
    private final RunCommitListener commitListener;

    /**
     * Creates a store that publishes no commits.
     *
     * @param runs run repository
     * @param artifacts artifact repository
     * @param codec JSON codec
     * @param graph workflow graph runs are restored against
     * @param transactionManager transaction manager
     */
    public JpaRunStore(RunRepository runs, ArtifactRepository artifacts, JsonCodec codec, WorkflowGraph graph,
                       PlatformTransactionManager transactionManager) {
        this(runs, artifacts, codec, graph, transactionManager, RunCommitListener.NONE);
    }

    /**
     * Creates the store with the listener of committed changes (the run event bus), when the context has one.
     *
     * @param runs run repository
     * @param artifacts artifact repository
     * @param codec JSON codec
     * @param graph workflow graph runs are restored against
     * @param transactionManager transaction manager
     * @param commitListener receives the committed changes, when available
     */
    @Autowired
    public JpaRunStore(RunRepository runs, ArtifactRepository artifacts, JsonCodec codec, WorkflowGraph graph,
                       PlatformTransactionManager transactionManager,
                       ObjectProvider<RunCommitListener> commitListener) {
        this(runs, artifacts, codec, graph, transactionManager,
                commitListener.getIfAvailable(() -> RunCommitListener.NONE));
    }

    /**
     * Creates the store with a listener of committed changes.
     *
     * @param runs run repository
     * @param artifacts artifact repository
     * @param codec JSON codec
     * @param graph workflow graph runs are restored against
     * @param transactionManager transaction manager
     * @param commitListener receives every committed change
     */
    public JpaRunStore(RunRepository runs, ArtifactRepository artifacts, JsonCodec codec, WorkflowGraph graph,
                       PlatformTransactionManager transactionManager, RunCommitListener commitListener) {
        this.commitListener = commitListener;
        this.runs = runs;
        this.artifacts = artifacts;
        this.codec = codec;
        this.graph = graph;
        this.transactions = new TransactionTemplate(transactionManager);
        this.readTransactions = new TransactionTemplate(transactionManager);
        this.readTransactions.setReadOnly(true);
    }

    @Override
    public void save(Run run) {
        RunSnapshot snapshot = run.toSnapshot(codec);
        String json = codec.toJson(snapshot);
        Listing listing = listing(run, snapshot, List.of());
        transactions.executeWithoutResult(status -> publishAfterCommit(persist(snapshot, json, listing), snapshot,
                List.of()));
    }

    /**
     * Saves the run snapshot and its new artifacts and appends the audit events of the mutation in one
     * transaction; when any part fails, nothing is persisted and the exception propagates to the engine.
     *
     * @param run the run (live aggregate; serialized before the transaction starts and not retained)
     * @param events the audit events of the mutation, in sequence order (may be empty)
     * @param auditSink the engine's audit sink (the JPA sink joins this transaction)
     */
    @Override
    public void commit(Run run, List<AuditEvent> events, AuditSink auditSink) {
        RunSnapshot snapshot = run.toSnapshot(codec);
        String json = codec.toJson(snapshot);
        List<AuditEvent> batch = List.copyOf(events);
        Listing listing = listing(run, snapshot, batch);
        transactions.executeWithoutResult(status -> {
            RunListItem item = persist(snapshot, json, listing);
            auditSink.append(batch);
            publishAfterCommit(item, snapshot, batch);
        });
    }

    /**
     * Hands the change to the commit listener once the surrounding transaction has committed (never after a
     * rollback). The listener runs on the committing thread; its failures are logged and never undo the commit.
     */
    private void publishAfterCommit(RunListItem item, RunSnapshot snapshot, List<AuditEvent> batch) {
        if (commitListener == RunCommitListener.NONE) {
            return;
        }
        RunCommitted committed = new RunCommitted(item, snapshot, batch);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            notifyListener(committed);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                notifyListener(committed);
            }
        });
    }

    private void notifyListener(RunCommitted committed) {
        try {
            commitListener.committed(committed);
        } catch (RuntimeException e) {
            LOG.warn("run commit listener failed runId={} error={}", committed.runId(), e.toString());
        }
    }

    private RunListItem persist(RunSnapshot snapshot, String json, Listing listing) {
        RunEntity entity = runs.findById(snapshot.id())
                .orElseGet(() -> new RunEntity(snapshot.id(), snapshot.scenarioKey(), snapshot.createdAt()));
        entity.update(snapshot.status().name(),
                snapshot.haltReason() == null ? null : snapshot.haltReason().name(), snapshot.requirement(),
                snapshot.planVersion(), snapshot.updatedAt(), snapshot.endedAt(), json);
        entity.describe(listing.autonomy(), listing.workspaceMode(),
                listing.startedBy() != null || entity.getStartedBy() != null ? listing.startedBy() : listing.tagStarter(),
                listing.riskLevel(), listing.nodeId(), listing.auditHeadHash());
        entity.progress(listing.pendingActions(), listing.stagesSucceeded(), listing.stagesTotal());
        RunEntity saved = runs.save(entity);
        insertNewArtifacts(snapshot);
        return item(saved);
    }

    /**
     * Returns the list row of an entity: what the list query reads from the table, with the requirement column in
     * full.
     *
     * @param entity a row
     * @return the list item
     */
    static RunListItem item(RunEntity entity) {
        return new RunListItem(entity.getId(), entity.getScenario(), entity.getStatus(), entity.getHaltReason(),
                entity.getRequirement(), entity.getPlanVersion(), entity.getCreatedAt(), entity.getUpdatedAt(),
                entity.getEndedAt(), entity.getAutonomy(), entity.getWorkspaceMode(), entity.getStartedBy(),
                entity.getRiskLevel(), entity.getNodeId(), entity.getPendingActions(), entity.getStagesSucceeded(),
                entity.getStagesTotal());
    }

    /**
     * Computes the listing columns of a run (under the engine's run lock, before the transaction starts).
     */
    private static Listing listing(Run run, RunSnapshot snapshot, List<AuditEvent> batch) {
        String startedBy = null;
        String nodeId = null;
        for (AuditEvent event : batch) {
            if (event.type() == AuditEventType.RUN_CREATED) {
                startedBy = event.actor().id();
            }
            if (event.data().get(NODE_ID_KEY) instanceof String node && !node.isBlank()) {
                nodeId = node;
            }
        }
        String riskLevel;
        try {
            riskLevel = run.currentRisk().level().name();
        } catch (RuntimeException e) {
            LOG.warn("risk level of run could not be computed for its listing column runId={} error={}", run.id(),
                    e.toString());
            riskLevel = null;
        }
        int pending = (int) snapshot.stages().stream().filter(s -> s.status().isAwaitingHuman()).count();
        int succeeded = (int) snapshot.stages().stream().filter(s -> s.status() == StageStatus.SUCCEEDED).count();
        return new Listing(snapshot.options().autonomy().name(), snapshot.options().workspace().name(), startedBy,
                snapshot.options().tags().get(CREATED_BY_TAG), riskLevel, nodeId, snapshot.auditHeadHash(), pending,
                succeeded, snapshot.stages().size());
    }

    /**
     * The listing columns of one save.
     *
     * @param autonomy autonomy level name
     * @param workspaceMode workspace mode name
     * @param startedBy actor of the RUN_CREATED event of this commit, or null
     * @param tagStarter the {@value #CREATED_BY_TAG} run tag, or null
     * @param riskLevel current risk level name, or null
     * @param nodeId node id of the last event of this commit that records one, or null
     * @param auditHeadHash audit chain head of the snapshot, or null
     * @param pendingActions stages waiting for a human
     * @param stagesSucceeded succeeded stages
     * @param stagesTotal stages of the run
     */
    private record Listing(String autonomy, String workspaceMode, @Nullable String startedBy,
                           @Nullable String tagStarter, @Nullable String riskLevel, @Nullable String nodeId,
                           @Nullable String auditHeadHash, int pendingActions, int stagesSucceeded, int stagesTotal) {
    }

    private void insertNewArtifacts(RunSnapshot snapshot) {
        if (snapshot.artifacts().isEmpty()) {
            return;
        }
        Set<String> persisted = artifacts.findIdsByRunId(snapshot.id());
        List<ArtifactEntity> fresh = new ArrayList<>();
        for (RunSnapshot.ArtifactSnapshot a : snapshot.artifacts()) {
            if (!persisted.contains(a.id())) {
                fresh.add(new ArtifactEntity(a.id(), a.runId(), a.stageId(), a.kind().name(), a.version(),
                        a.planVersion(), a.hash(), a.payloadJson(), codec.toJson(a.provenance()), a.createdAt()));
            }
        }
        if (!fresh.isEmpty()) {
            artifacts.saveAll(fresh);
        }
    }

    @Override
    public Optional<Run> load(String id) {
        return readTransactions.execute(status -> runs.findById(id).map(this::restore));
    }

    /**
     * Loads every persisted run, oldest first. A snapshot that cannot be restored is logged and skipped so that one
     * corrupt row cannot prevent the control plane from starting.
     *
     * @return the runs
     */
    @Override
    public List<Run> loadAll() {
        List<RunEntity> entities = readTransactions.execute(status -> runs.findAllByOrderByCreatedAtAscIdAsc());
        List<Run> loaded = new ArrayList<>();
        for (RunEntity entity : entities == null ? List.<RunEntity>of() : entities) {
            try {
                loaded.add(restore(entity));
            } catch (RuntimeException e) {
                LOG.error("skipping run whose snapshot cannot be restored runId={} error={}", entity.getId(),
                        e.toString());
            }
        }
        return List.copyOf(loaded);
    }

    private Run restore(RunEntity entity) {
        RunSnapshot snapshot = codec.fromJson(entity.getSnapshotJson(), RunSnapshot.class);
        return Run.fromSnapshot(snapshot, graph, codec);
    }
}
