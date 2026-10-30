package com.agenticsdlc.orchestrator.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;

class AuditSinkTest {

    private static List<AuditEvent> events(String runId, int count) {
        return LongStream.rangeClosed(1, count).mapToObj(seq -> new AuditEvent(seq,
                Instant.parse("2026-01-01T00:00:00Z"), runId, null, AuditEventType.STAGE_READY, Actor.system(),
                "event " + seq, Map.of())).toList();
    }

    @Test
    void inMemorySinkReturnsBoundedPagesInSequenceOrder() {
        InMemoryAuditSink sink = new InMemoryAuditSink();
        sink.append(events("run-1", 5));
        sink.append(events("run-2", 2));

        assertThat(sink.forRun("run-1", 0, 2)).extracting(AuditEvent::seq).containsExactly(1L, 2L);
        assertThat(sink.forRun("run-1", 2, 2)).extracting(AuditEvent::seq).containsExactly(3L, 4L);
        assertThat(sink.forRun("run-1", 4, 10)).extracting(AuditEvent::seq).containsExactly(5L);
        assertThat(sink.forRun("run-1", 5, 10)).isEmpty();
        assertThat(sink.forRun("unknown", 0, 10)).isEmpty();
        assertThatThrownBy(() -> sink.forRun("run-1", 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theDefaultImplementationTruncatesTheUnboundedQuery() {
        List<AuditEvent> stored = new ArrayList<>(events("run-1", 4));
        AuditSink sink = new AuditSink() {
            @Override
            public void append(List<AuditEvent> events) {
                stored.addAll(events);
            }

            @Override
            public List<AuditEvent> forRun(String runId, long afterSeq) {
                return stored.stream().filter(e -> e.runId().equals(runId) && e.seq() > afterSeq).toList();
            }
        };

        assertThat(sink.forRun("run-1", 1, 2)).extracting(AuditEvent::seq).containsExactly(2L, 3L);
        assertThat(sink.forRun("run-1", 0, 100)).hasSize(4);
        assertThatThrownBy(() -> sink.forRun("run-1", 0, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
