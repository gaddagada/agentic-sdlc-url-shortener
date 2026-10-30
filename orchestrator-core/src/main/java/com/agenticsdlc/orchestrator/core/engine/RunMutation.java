package com.agenticsdlc.orchestrator.core.engine;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.audit.Actor;
import com.agenticsdlc.orchestrator.core.audit.AuditEvent;
import com.agenticsdlc.orchestrator.core.audit.AuditEventType;
import com.agenticsdlc.orchestrator.core.model.Run;

/**
 * One command's unit of work on a run, executed under the run lock: the run, a fixed timestamp and the
 * buffered audit events that are flushed after the run is persisted.
 */
final class RunMutation {

    final RunRegistry.RunHolder holder;
    final Run run;
    final Instant now;
    private final List<AuditEvent> events = new ArrayList<>();

    RunMutation(RunRegistry.RunHolder holder, Instant now) {
        this.holder = holder;
        this.run = holder.run;
        this.now = now;
    }

    /**
     * Buffers an audit event with the next sequence number of the run.
     */
    void audit(AuditEventType type, @Nullable String stageId, Actor actor, String message, Map<String, Object> data) {
        events.add(new AuditEvent(run.nextAuditSeq(), now, run.id(), stageId, type, actor, message, data));
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
