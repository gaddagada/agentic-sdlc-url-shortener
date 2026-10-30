package com.agenticsdlc.orchestrator.app.persistence;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import com.agenticsdlc.orchestrator.core.audit.AuditEvent;
import com.agenticsdlc.orchestrator.core.audit.AuditSink;
import com.agenticsdlc.orchestrator.core.model.Run;
import com.agenticsdlc.orchestrator.core.port.RunLease;
import com.agenticsdlc.orchestrator.core.port.RunLeaseProvider;
import com.agenticsdlc.orchestrator.core.port.RunStore;

/**
 * Simulates the crash of one engine "node" in a multi-node test: the node's run store, audit sink and lease provider
 * are wrapped, and once {@link #crash()} is called every call through them fails. The node can then neither write
 * (no snapshot, audit event or artifact reaches the shared database) nor touch a lease (no renewal, release or
 * takeover), exactly like a process that died while holding its leases; its threads may keep running, but nothing
 * they do reaches the other nodes. Unlike {@code close()}, crashing releases nothing.
 */
final class NodeCrash {

    private final AtomicBoolean crashed = new AtomicBoolean();

    /** Crashes the node: every later call through the wrapped ports fails. */
    void crash() {
        crashed.set(true);
    }

    private void alive() {
        if (crashed.get()) {
            throw new IllegalStateException("simulated node crash: this node can no longer reach the database");
        }
    }

    /**
     * Wraps the node's run store.
     *
     * @param store the shared store
     * @return the store as seen by the node
     */
    RunStore store(RunStore store) {
        return new RunStore() {
            @Override
            public void commit(Run run, List<AuditEvent> events, AuditSink auditSink) {
                alive();
                store.commit(run, events, auditSink);
            }

            @Override
            public void save(Run run) {
                alive();
                store.save(run);
            }

            @Override
            public Optional<Run> load(String id) {
                alive();
                return store.load(id);
            }

            @Override
            public List<Run> loadAll() {
                alive();
                return store.loadAll();
            }
        };
    }

    /**
     * Wraps the node's audit sink.
     *
     * @param sink the shared sink
     * @return the sink as seen by the node
     */
    AuditSink audit(AuditSink sink) {
        return new AuditSink() {
            @Override
            public void append(List<AuditEvent> events) {
                alive();
                sink.append(events);
            }

            @Override
            public Optional<AuditEvent> lastEvent(String runId) {
                alive();
                return sink.lastEvent(runId);
            }

            @Override
            public List<AuditEvent> forRun(String runId, long afterSeq) {
                alive();
                return sink.forRun(runId, afterSeq);
            }

            @Override
            public List<AuditEvent> forRun(String runId, long afterSeq, int limit) {
                alive();
                return sink.forRun(runId, afterSeq, limit);
            }
        };
    }

    /**
     * Wraps the node's lease provider.
     *
     * @param leases the shared lease provider
     * @return the provider as seen by the node
     */
    RunLeaseProvider leases(RunLeaseProvider leases) {
        return new RunLeaseProvider() {
            @Override
            public Optional<RunLease> tryAcquire(String runId, String ownerId, Duration ttl) {
                alive();
                return leases.tryAcquire(runId, ownerId, ttl);
            }

            @Override
            public boolean renew(RunLease lease, Duration ttl) {
                alive();
                return leases.renew(lease, ttl);
            }

            @Override
            public void release(RunLease lease) {
                alive();
                leases.release(lease);
            }

            @Override
            public Optional<RunLease> reclaim(String runId, String ownerId, Duration ttl) {
                alive();
                return leases.reclaim(runId, ownerId, ttl);
            }

            @Override
            public Optional<String> holderOf(String runId) {
                alive();
                return leases.holderOf(runId);
            }
        };
    }
}
