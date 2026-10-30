package com.agenticsdlc.orchestrator.core.engine;

import static com.agenticsdlc.orchestrator.core.testsupport.EngineHarness.ALICE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.agenticsdlc.orchestrator.core.agent.deterministic.DeterministicAgents;
import com.agenticsdlc.orchestrator.core.artifact.ReleaseReadiness;
import com.agenticsdlc.orchestrator.core.audit.AuditChain;
import com.agenticsdlc.orchestrator.core.audit.AuditEvent;
import com.agenticsdlc.orchestrator.core.audit.AuditEventType;
import com.agenticsdlc.orchestrator.core.audit.AuditSink;
import com.agenticsdlc.orchestrator.core.audit.ChainVerification;
import com.agenticsdlc.orchestrator.core.audit.ChangeRecordSigner;
import com.agenticsdlc.orchestrator.core.audit.HmacChangeRecordSigner;
import com.agenticsdlc.orchestrator.core.graph.WorkflowDefinitions;
import com.agenticsdlc.orchestrator.core.json.JsonCodec;
import com.agenticsdlc.orchestrator.core.model.AutonomyLevel;
import com.agenticsdlc.orchestrator.core.model.Run;
import com.agenticsdlc.orchestrator.core.model.RunOptions;
import com.agenticsdlc.orchestrator.core.model.RunSnapshot;
import com.agenticsdlc.orchestrator.core.model.RunStatus;
import com.agenticsdlc.orchestrator.core.model.StageStatus;
import com.agenticsdlc.orchestrator.core.port.RunStore;
import com.agenticsdlc.orchestrator.core.testsupport.EngineHarness;

import tools.jackson.databind.node.ObjectNode;

/**
 * The engine hash-chains every audit event of a run (across commands, failed commits and reloads) and signs the
 * finalized release change record.
 */
class OrchestrationEngineAuditChainTest {

    private static final String DESIGN = WorkflowDefinitions.DESIGN;
    private static final String RELEASE = WorkflowDefinitions.RELEASE_READINESS;
    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    private final EngineHarness harness = new EngineHarness();

    @AfterEach
    void close() {
        harness.close();
    }

    private static void assertValidChain(List<AuditEvent> events, Run run) {
        ChainVerification verification = AuditChain.verify(events);
        assertThat(verification.valid()).as(verification.summary()).isTrue();
        assertThat(verification.length()).isEqualTo(events.size()).isEqualTo(run.auditSeq());
        assertThat(events).allSatisfy(e -> assertThat(e.signed()).isTrue());
        assertThat(run.auditHeadHash()).isEqualTo(events.getLast().hash());
    }

    @Test
    void aCompleteDeterministicRunProducesAValidHashChain() {
        harness.agents(DeterministicAgents.all(null));
        OrchestrationEngine engine = harness.start();
        String id = engine.start(DeterministicPipelineTest.GREENFIELD, "greenfield", RunOptions.defaults()).id();
        harness.awaitApproval(id, DESIGN);
        engine.approve(id, DESIGN, ALICE, "design approved");
        harness.awaitApproval(id, RELEASE);
        engine.approve(id, RELEASE, ALICE, "ship it");
        Run done = harness.awaitStatus(id, RunStatus.COMPLETED);

        List<AuditEvent> events = harness.events(id);
        assertThat(events.size()).isGreaterThan(40);
        assertValidChain(events, done);
        assertThat(events.getFirst().prevHash()).isEqualTo(AuditChain.GENESIS);
        assertThat(events.getFirst().type()).isEqualTo(AuditEventType.RUN_CREATED);
        assertThat(events.getFirst().data()).containsEntry("nodeId", EngineConfig.defaultNodeId());
        assertThat(events).filteredOn(e -> e.type() == AuditEventType.STAGE_STARTED)
                .allSatisfy(e -> assertThat(e.data()).containsEntry("nodeId", EngineConfig.defaultNodeId()));

        Run persisted = harness.store.load(id).orElseThrow();
        assertThat(persisted.auditHeadHash()).isEqualTo(done.auditHeadHash());
        RunSnapshot snapshot = persisted.toSnapshot();
        JsonCodec codec = JsonCodec.standard();
        RunSnapshot roundTripped = codec.fromJson(codec.toJson(snapshot), RunSnapshot.class);
        assertThat(roundTripped.auditHeadHash()).isEqualTo(done.auditHeadHash());

        ObjectNode legacy = (ObjectNode) codec.toTree(snapshot);
        legacy.remove("auditHeadHash");
        assertThat(codec.fromJson(codec.toJson(legacy), RunSnapshot.class).auditHeadHash())
                .as("snapshots written before hash chaining still load").isNull();
    }

    @Test
    void aRunReloadedByAnotherEngineContinuesTheChain() {
        OrchestrationEngine first = harness.start();
        String id = first.start("Create short links and redirect with HTTP 302.", "reload", RunOptions.defaults())
                .id();
        harness.awaitApproval(id, DESIGN);
        first.close();

        EngineHarness second = new EngineHarness(harness.store, harness.audit);
        try {
            OrchestrationEngine engine = second.start();
            engine.approve(id, DESIGN, ALICE, null);
            second.awaitApproval(id, RELEASE);
            engine.approve(id, RELEASE, ALICE, null);
            Run done = second.awaitStatus(id, RunStatus.COMPLETED);
            assertValidChain(harness.events(id), done);
        } finally {
            second.close();
        }
    }

    @Test
    void aFailedCommitDoesNotLeaveAGapOrABrokenLink() {
        AtomicInteger failNext = new AtomicInteger();
        harness.storeDecorator(store -> new DelegatingStore(store) {
            @Override
            public void commit(Run run, List<AuditEvent> events, AuditSink auditSink) {
                if (failNext.getAndSet(0) > 0) {
                    throw new IllegalStateException("database unavailable");
                }
                super.commit(run, events, auditSink);
            }
        });
        OrchestrationEngine engine = harness.start();
        String id = engine.start("Create short links and redirect with HTTP 302.", "gap", RunOptions.defaults()).id();
        harness.awaitApproval(id, DESIGN);
        long committedSeq = harness.audit.lastEvent(id).orElseThrow().seq();

        failNext.set(1);
        assertThatThrownBy(() -> engine.approve(id, DESIGN, ALICE, "ok"))
                .hasMessageContaining("database unavailable");
        assertThat(harness.store.load(id).orElseThrow().stage(DESIGN).status())
                .isEqualTo(StageStatus.AWAITING_APPROVAL);

        // the in-memory run is ahead of the store (design approved, branches running); the next commits continue the
        // chain from the last committed event: the lost events' sequence numbers are reused, nothing is re-sent
        harness.awaitApproval(id, RELEASE);
        engine.approve(id, RELEASE, ALICE, null);
        Run done = harness.awaitStatus(id, RunStatus.COMPLETED);
        List<AuditEvent> events = harness.events(id);
        assertValidChain(events, done);
        assertThat(events.get((int) committedSeq).type()).as("the first event after the failed commit")
                .isNotEqualTo(AuditEventType.APPROVAL_GRANTED);
        assertThat(events).filteredOn(e -> e.type() == AuditEventType.APPROVAL_GRANTED)
                .extracting(AuditEvent::stageId).containsExactly(RELEASE);
    }

    @Test
    void aFailedCommitOfTheAtomicInMemoryStoreLeavesNeitherTheRunChangeNorItsEventsAndRestoresTheChainHead() {
        AtomicInteger failAppend = new AtomicInteger();
        harness.storeDecorator(store -> new DelegatingStore(store) {
            @Override
            public void commit(Run run, List<AuditEvent> events, AuditSink auditSink) {
                AuditSink sink = failAppend.getAndSet(0) == 0 ? auditSink : new AuditSink() {
                    @Override
                    public void append(List<AuditEvent> batch) {
                        throw new IllegalStateException("audit log unavailable");
                    }

                    @Override
                    public List<AuditEvent> forRun(String runId, long afterSeq) {
                        return auditSink.forRun(runId, afterSeq);
                    }
                };
                store.commit(run, events, sink); // the in-memory store's own commit
            }
        });
        OrchestrationEngine engine = harness.start();
        String id = engine.start("Create short links and redirect with HTTP 302.", "atomic", RunOptions.defaults())
                .id();
        harness.awaitApproval(id, DESIGN);
        Run before = harness.store.load(id).orElseThrow();
        AuditEvent last = harness.audit.lastEvent(id).orElseThrow();

        failAppend.set(1);
        assertThatThrownBy(() -> engine.stop(id, ALICE, "pause")).hasMessageContaining("audit log unavailable");

        Run stored = harness.store.load(id).orElseThrow();
        assertThat(stored.status()).as("the run change was rolled back").isEqualTo(RunStatus.WAITING_HUMAN)
                .isEqualTo(before.status());
        assertThat(stored.auditSeq()).isEqualTo(last.seq());
        assertThat(stored.updatedAt()).isEqualTo(before.updatedAt());
        assertThat(harness.audit.lastEvent(id)).as("no event of the failed commit").contains(last);
        Run live = engine.get(id);
        assertThat(live.auditSeq()).as("the chain head is restored").isEqualTo(last.seq());
        assertThat(live.auditHeadHash()).isEqualTo(last.hash());

        engine.resume(id, ALICE);
        harness.awaitApproval(id, DESIGN);
        engine.approve(id, DESIGN, ALICE, null);
        harness.awaitApproval(id, RELEASE);
        engine.approve(id, RELEASE, ALICE, null);
        assertValidChain(harness.events(id), harness.awaitStatus(id, RunStatus.COMPLETED));
    }

    @Test
    void aSnapshotThatIsAheadOfTheAuditLogIsResynchronizedOnLoad() {
        // the default commit is not atomic: the run is saved, then the append fails
        AtomicInteger failAppend = new AtomicInteger();
        harness.storeDecorator(store -> new DelegatingStore(store) {
            @Override
            public void commit(Run run, List<AuditEvent> events, AuditSink auditSink) {
                save(run);
                if (failAppend.getAndSet(0) > 0) {
                    throw new IllegalStateException("audit log unavailable");
                }
                auditSink.append(events);
            }
        });
        OrchestrationEngine first = harness.start();
        String id = first.start("Create short links and redirect with HTTP 302.", "ahead", RunOptions.defaults()).id();
        harness.awaitApproval(id, DESIGN);
        failAppend.set(1);
        assertThatThrownBy(() -> first.stop(id, ALICE, "pause")).hasMessageContaining("audit log unavailable");
        Run saved = harness.store.load(id).orElseThrow();
        assertThat(saved.auditSeq()).as("the snapshot is ahead of the log")
                .isGreaterThan(harness.audit.lastEvent(id).orElseThrow().seq());
        first.close();

        EngineHarness second = new EngineHarness(harness.store, harness.audit);
        try {
            OrchestrationEngine engine = second.start();
            engine.resume(id, ALICE);
            second.awaitApproval(id, DESIGN);
            engine.approve(id, DESIGN, ALICE, null);
            second.awaitApproval(id, RELEASE);
            engine.approve(id, RELEASE, ALICE, null);
            assertValidChain(harness.events(id), second.awaitStatus(id, RunStatus.COMPLETED));
        } finally {
            second.close();
        }
    }

    @Test
    void theFinalizedChangeRecordIsSignedWithTheConfiguredKey() {
        HmacChangeRecordSigner signer = new HmacChangeRecordSigner(KEY, "release-key-1");
        harness.config(b -> b.changeRecordSigner(signer));
        OrchestrationEngine engine = harness.start();
        String id = engine.start("Create short links and redirect with HTTP 302.", "signed",
                RunOptions.defaults().withAutonomy(AutonomyLevel.FULL_AUTO)).id();
        Run atRelease = harness.awaitApproval(id, RELEASE);
        assertThat(atRelease.acceptedPayload(ReleaseReadiness.class)).isEmpty();
        engine.approve(id, RELEASE, ALICE, "ship it");
        Run done = harness.awaitStatus(id, RunStatus.COMPLETED);

        ReleaseReadiness.ChangeRecord record = done.acceptedPayload(ReleaseReadiness.class).orElseThrow()
                .changeRecord();
        ReleaseReadiness.Signature signature = record.signature();
        assertThat(signature).isNotNull();
        assertThat(signature.algorithm()).isEqualTo("HmacSHA256");
        assertThat(signature.keyId()).isEqualTo("release-key-1");
        assertThat(signature.signedAt()).isEqualTo(done.endedAt().toString());
        assertThat(record.approvals()).extracting(ReleaseReadiness.ApprovalRecord::approver).containsExactly("alice");
        assertThat(signer.verify(record)).isTrue();
        assertThat(new HmacChangeRecordSigner(KEY, "release-key-1")
                .verify(ChangeRecordSigner.canonicalPayload(record), signature.value())).isTrue();
        assertThat(new HmacChangeRecordSigner("fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8),
                "release-key-1").verify(record)).as("a different key").isFalse();

        AuditEvent finalized = harness.audit.ofType(id, AuditEventType.ARTIFACT_ACCEPTED).getLast();
        assertThat(finalized.data()).containsEntry("signatureAlgorithm", "HmacSHA256")
                .containsEntry("keyId", "release-key-1").doesNotContainKey("signatureError");
        assertThat(finalized.message()).contains("signed (HmacSHA256, key release-key-1)");
        assertValidChain(harness.events(id), done);
    }

    @Test
    void theDefaultSignerLeavesTheChangeRecordUnsigned() {
        OrchestrationEngine engine = harness.start();
        String id = engine.start("Create short links and redirect with HTTP 302.", "unsigned",
                RunOptions.defaults().withAutonomy(AutonomyLevel.FULL_AUTO)).id();
        harness.awaitApproval(id, RELEASE);
        engine.approve(id, RELEASE, ALICE, null);
        Run done = harness.awaitStatus(id, RunStatus.COMPLETED);

        assertThat(done.acceptedPayload(ReleaseReadiness.class).orElseThrow().changeRecord().signature()).isNull();
        assertThat(harness.audit.ofType(id, AuditEventType.ARTIFACT_ACCEPTED).getLast().data())
                .containsEntry("signatureAlgorithm", "none").containsEntry("keyId", "none");
    }

    @Test
    void aFailingSignerDoesNotBlockCompletionAndTheMissingSignatureIsAudited() {
        harness.config(b -> b.changeRecordSigner(new ChangeRecordSigner() {
            @Override
            public String algorithm() {
                return "kms-rsa";
            }

            @Override
            public String keyId() {
                return "kms-key";
            }

            @Override
            public String sign(String canonicalPayload) {
                throw new IllegalStateException("KMS unavailable");
            }

            @Override
            public boolean verify(String canonicalPayload, String signature) {
                return false;
            }
        }));
        OrchestrationEngine engine = harness.start();
        String id = engine.start("Create short links and redirect with HTTP 302.", "kms-down",
                RunOptions.defaults().withAutonomy(AutonomyLevel.FULL_AUTO)).id();
        harness.awaitApproval(id, RELEASE);
        engine.approve(id, RELEASE, ALICE, null);
        Run done = harness.awaitStatus(id, RunStatus.COMPLETED);

        assertThat(done.acceptedPayload(ReleaseReadiness.class).orElseThrow().changeRecord().signature()).isNull();
        Map<String, Object> data = harness.audit.ofType(id, AuditEventType.ARTIFACT_ACCEPTED).getLast().data();
        assertThat(data).containsEntry("signatureAlgorithm", "none").containsEntry("signer", "kms-rsa");
        assertThat(data.get("signatureError")).asString().contains("KMS unavailable");
    }

    /** A store that delegates to another one (to override {@link RunStore#commit}). */
    private static class DelegatingStore implements RunStore {
        private final RunStore delegate;

        DelegatingStore(RunStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public void save(Run run) {
            delegate.save(run);
        }

        @Override
        public Optional<Run> load(String id) {
            return delegate.load(id);
        }

        @Override
        public List<Run> loadAll() {
            return delegate.loadAll();
        }
    }
}
