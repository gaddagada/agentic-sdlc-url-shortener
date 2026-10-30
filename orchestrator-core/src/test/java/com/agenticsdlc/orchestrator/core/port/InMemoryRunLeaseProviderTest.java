package com.agenticsdlc.orchestrator.core.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import com.agenticsdlc.orchestrator.core.testsupport.MutableClock;

class InMemoryRunLeaseProviderTest {

    private static final Duration TTL = Duration.ofSeconds(60);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-30T10:00:00Z"));
    private final InMemoryRunLeaseProvider leases = new InMemoryRunLeaseProvider(clock);

    @Test
    void aLeaseIsExclusiveUntilReleased() {
        RunLease lease = leases.tryAcquire("run-1", "node-a", TTL).orElseThrow();
        assertThat(lease.runId()).isEqualTo("run-1");
        assertThat(lease.ownerId()).isEqualTo("node-a");
        assertThat(lease.expiresAt()).isEqualTo(Instant.parse("2026-10-30T10:01:00Z"));
        assertThat(leases.holderOf("run-1")).contains("node-a");

        assertThat(leases.tryAcquire("run-1", "node-b", TTL)).isEmpty();
        assertThat(leases.tryAcquire("run-1", "node-a", TTL)).as("not re-entrant").isEmpty();
        assertThat(leases.tryAcquire("run-2", "node-b", TTL)).as("other runs are independent").isPresent();

        leases.release(new RunLease("run-1", "node-b", "forged-token", lease.expiresAt()));
        assertThat(leases.holderOf("run-1")).as("a release needs the token").contains("node-a");
        leases.release(lease);
        assertThat(leases.holderOf("run-1")).isEmpty();
        assertThat(leases.current("run-1")).isEmpty();
        assertThat(leases.tryAcquire("run-1", "node-b", TTL)).isPresent();
    }

    @Test
    void renewalExtendsTheLeaseAndFailsOnceTheTokenNoLongerHoldsTheRun() {
        RunLease lease = leases.tryAcquire("run-1", "node-a", TTL).orElseThrow();
        clock.advance(Duration.ofSeconds(50));
        assertThat(leases.renew(lease, TTL)).isTrue();
        clock.advance(Duration.ofSeconds(50));
        assertThat(leases.holderOf("run-1")).as("renewed at +50 s, still valid at +100 s").contains("node-a");
        assertThat(leases.current("run-1").orElseThrow().expiresAt()).isEqualTo(Instant.parse("2026-10-30T10:01:50Z"));

        clock.advance(Duration.ofSeconds(11));
        assertThat(leases.holderOf("run-1")).as("expired").isEmpty();
        assertThat(leases.renew(lease, TTL)).as("an expired lease nobody took over can be renewed").isTrue();
        assertThat(leases.holderOf("run-1")).contains("node-a");

        clock.advance(Duration.ofSeconds(61));
        RunLease takeover = leases.tryAcquire("run-1", "node-b", TTL).orElseThrow();
        assertThat(takeover.token()).isNotEqualTo(lease.token());
        assertThat(leases.renew(lease, TTL)).as("the old token lost the run").isFalse();
        leases.release(lease);
        assertThat(leases.holderOf("run-1")).contains("node-b");
        assertThat(leases.renew(new RunLease("unknown", "x", "t", Instant.EPOCH), TTL)).isFalse();
    }

    @Test
    void anExpiredLeaseCanBeTakenOverExactlyAtItsExpiry() {
        leases.tryAcquire("run-1", "node-a", TTL).orElseThrow();
        clock.advance(TTL.minusMillis(1));
        assertThat(leases.tryAcquire("run-1", "node-b", TTL)).isEmpty();
        clock.advance(Duration.ofMillis(1));
        assertThat(leases.tryAcquire("run-1", "node-b", TTL)).isPresent();
        assertThat(leases.holderOf("run-1")).contains("node-b");
    }

    @Test
    void invalidDurationsAreRejected() {
        assertThatThrownBy(() -> leases.tryAcquire("run-1", "node-a", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        RunLease lease = leases.tryAcquire("run-1", "node-a", TTL).orElseThrow();
        assertThatThrownBy(() -> leases.renew(lease, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrentAcquisitionsGrantExactlyOneLease() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Optional<RunLease>>> attempts = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                String owner = "node-" + i;
                attempts.add(pool.submit(() -> {
                    go.await();
                    return leases.tryAcquire("contended", owner, TTL);
                }));
            }
            go.countDown();
            int granted = 0;
            for (Future<Optional<RunLease>> attempt : attempts) {
                granted += attempt.get().isPresent() ? 1 : 0;
            }
            assertThat(granted).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
