package com.agenticsdlc.orchestrator.app.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.agenticsdlc.orchestrator.core.graph.WorkflowGraph;
import com.agenticsdlc.orchestrator.core.json.JsonCodec;
import com.agenticsdlc.orchestrator.core.model.Run;
import com.agenticsdlc.orchestrator.core.model.RunSnapshot;
import com.agenticsdlc.orchestrator.core.port.RunStore;

/**
 * JPA implementation of the engine's {@link RunStore}: the run aggregate is persisted as one JSON snapshot
 * (optimistic {@code @Version}), and artifact versions not yet persisted are inserted into the {@code artifacts}
 * table.
 *
 * <p>The engine calls {@link #save(Run)} from its own (virtual) threads while holding the run lock and outside any
 * Spring-managed transaction, so every method demarcates its transaction explicitly with a
 * {@link TransactionTemplate} instead of relying on {@code @Transactional} proxies. The snapshot is serialized
 * synchronously before the transaction starts, so the live run is never read after {@code save} returns.</p>
 */
@Component
public class JpaRunStore implements RunStore {

    private static final Logger LOG = LoggerFactory.getLogger(JpaRunStore.class);

    private final RunRepository runs;
    private final ArtifactRepository artifacts;
    private final JsonCodec codec;
    private final WorkflowGraph graph;
    private final TransactionTemplate transactions;
    private final TransactionTemplate readTransactions;

    /**
     * Creates the store.
     *
     * @param runs run repository
     * @param artifacts artifact repository
     * @param codec JSON codec
     * @param graph workflow graph runs are restored against
     * @param transactionManager transaction manager
     */
    public JpaRunStore(RunRepository runs, ArtifactRepository artifacts, JsonCodec codec, WorkflowGraph graph,
                       PlatformTransactionManager transactionManager) {
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
        transactions.executeWithoutResult(status -> {
            RunEntity entity = runs.findById(snapshot.id())
                    .orElseGet(() -> new RunEntity(snapshot.id(), snapshot.scenarioKey(), snapshot.createdAt()));
            entity.update(snapshot.status().name(),
                    snapshot.haltReason() == null ? null : snapshot.haltReason().name(), snapshot.requirement(),
                    snapshot.planVersion(), snapshot.updatedAt(), snapshot.endedAt(), json);
            runs.save(entity);
            insertNewArtifacts(snapshot);
        });
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
