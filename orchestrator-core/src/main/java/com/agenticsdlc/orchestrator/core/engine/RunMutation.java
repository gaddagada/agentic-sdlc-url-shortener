package com.agenticsdlc.orchestrator.core.engine;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.audit.Actor;
import com.agenticsdlc.orchestrator.core.audit.AuditChain;
import com.agenticsdlc.orchestrator.core.audit.AuditEvent;
import com.agenticsdlc.orchestrator.core.audit.AuditEventType;
import com.agenticsdlc.orchestrator.core.model.Run;

/**
 * One command's unit of work on a run, executed under the run lock: the run, a fixed timestamp, the id of the
 * executing node and the buffered audit events that are flushed after the run is persisted.
 *
 * <p>This is the single place where audit events are created, so it is where they are hash-chained: each event
 * gets the next sequence number, {@code prevHash} = the run's current chain head and its own {@code hash}, which
 * becomes the new head ({@link Run#auditHeadHash()}, persisted with the run's snapshot). When the mutation is not
 * committed (the command failed or the commit threw), {@link #rollbackAuditChain()} restores the sequence and head
 * to the last committed event, so the next commit continues the chain without a gap.</p>
 */
final class RunMutation {

    final RunRegistry.RunHolder holder;
    final Run run;
    final Instant now;
    final String nodeId;
    private final List<AuditEvent> events = new ArrayList<>();
    private final long startSeq;
    private final @Nullable String startHead;

    RunMutation(RunRegistry.RunHolder holder, Instant now) {
        this(holder, now, EngineConfig.defaultNodeId());
    }

    RunMutation(RunRegistry.RunHolder holder, Instant now, String nodeId) {
        this.holder = holder;
        this.run = holder.run;
        this.now = now;
        this.nodeId = nodeId;
        this.startSeq = run.auditSeq();
        this.startHead = run.auditHeadHash();
    }

    /**
     * Buffers an audit event with the next sequence number of the run, linked to the run's hash chain. Messages
     * longer than {@link AuditEvent#MAX_MESSAGE_LENGTH} are abbreviated first.
     */
    void audit(AuditEventType type, @Nullable String stageId, Actor actor, String message, Map<String, Object> data) {
        long seq = run.nextAuditSeq();
        AuditEvent event = AuditChain.link(new AuditEvent(seq, now, run.id(), stageId, type, actor,
                boundedMessage(message), data), run.auditHeadHash());
        run.advanceAuditHead(Objects.requireNonNull(event.hash()));
        events.add(event);
    }

    /**
     * Discards the buffered events and restores the run's audit sequence and chain head to their values when the
     * mutation started (the last committed event).
     */
    void rollbackAuditChain() {
        events.clear();
        run.resetAuditChain(startSeq, startHead);
    }

    private static String boundedMessage(@Nullable String message) {
        if (message == null) {
            return "";
        }
        int max = AuditEvent.MAX_MESSAGE_LENGTH;
        return message.length() <= max ? message : message.substring(0, max - 3) + "...";
    }

    void audit(AuditEventType type, @Nullable String stageId, Actor actor, String message) {
        audit(type, stageId, actor, message, Map.of());
    }

    List<AuditEvent> events() {
        return List.copyOf(events);
    }

    /**
     * Builds an audit data map from key/value pairs, skipping null values and converting enums and
     * instants to strings and collections to lists of strings.
     */
    static Map<String, Object> data(@Nullable Object... keyValues) {
        Map<String, Object> data = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            Object key = keyValues[i];
            Object value = keyValues[i + 1];
            if (key == null || value == null) {
                continue;
            }
            data.put(key.toString(), normalize(value));
        }
        return data;
    }

    private static Object normalize(Object value) {
        return switch (value) {
            case Enum<?> e -> e.name();
            case Instant i -> i.toString();
            case Collection<?> c -> c.stream().map(String::valueOf).toList();
            case Map<?, ?> m -> {
                Map<String, Object> copy = new LinkedHashMap<>();
                m.forEach((k, v) -> {
                    if (k != null && v != null) {
                        copy.put(k.toString(), normalize(v));
                    }
                });
                yield copy;
            }
            case Number n -> n;
            case Boolean b -> b;
            default -> value.toString();
        };
    }
}
