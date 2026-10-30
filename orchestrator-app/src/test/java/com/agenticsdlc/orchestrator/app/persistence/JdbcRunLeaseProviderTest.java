package com.agenticsdlc.orchestrator.app.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.agenticsdlc.orchestrator.core.port.RunLease;

/**
 * {@link JdbcRunLeaseProvider} on H2 with the Flyway schema: acquisition, contention (also concurrent), expiry and
 * takeover, renewal by token (also after expiry), release by token and the holder query, all judged with the
 * provider's clock.
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JdbcRunLeaseProviderTest {

    private static final Duration TTL = Duration.ofSeconds(60);

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-30T10:00:00Z"));
    private JdbcRunLeaseProvider leases;

    @BeforeEach
    void create() {
        leases = new JdbcRunLeaseProvider(dataSource, transactionManager, clock);
    }

    @AfterEach
    void cleanUp() {
        JdbcClient.create(dataSource).sql("DELETE FROM run_leases").update();
    }

    @Test
    void aFreeRunIsLeasedAndItsRowIsStored() {
        RunLease lease = leases.tryAcquire("run-1", "node-a", TTL).orElseThrow();

        assertThat(lease.runId()).isEqualTo("run-1");
        assertThat(lease.ownerId()).isEqualTo("node-a");
        assertThat(lease.token()).isNotBlank().hasSizeLessThanOrEqualTo(64);
        assertThat(lease.expiresAt()).isEqualTo(clock.instant().plus(TTL));
        assertThat(leases.holderOf("run-1")).contains("node-a");
        assertThat(leases.current("run-1")).contains(lease);
        assertThat(leases.holderOf("run-2")).isEmpty();
    }

    @Test
    void aValidLeaseBlocksEveryOtherAcquisitionIncludingItsOwnersOne() {
        RunLease held = leases.tryAcquire("run-1", "node-a", TTL).orElseThrow();

        assertThat(leases.tryAcquire("run-1", "node-b", TTL)).as("contention").isEmpty();
        assertThat(leases.tryAcquire("run-1", "node-a", TTL)).as("not re-entrant").isEmpty();
        clock.advance(TTL.minusMillis(1));
        assertThat(leases.tryAcquire("run-1", "node-b", TTL)).as("still valid one millisecond before expiry")
                .isEmpty();
        assertThat(leases.current("run-1")).contains(held);
    }

    @Test
    void anExpiredLeaseIsTakenOverWithANewToken() {
        RunLease stale = leases.tryAcquire("run-1", "node-a", TTL).orElseThrow();
        clock.advance(TTL);

        assertThat(leases.holderOf("run-1")).as("expired at expiresAt").isEmpty();
        RunLease taken = leases.tryAcquire("run-1", "node-b", TTL).orElseThrow();

        assertThat(taken.token()).isNotEqualTo(stale.token());
        assertThat(leases.holderOf("run-1")).contains("node-b");
        assertThat(leases.renew(stale, TTL)).as("the old token lost the run").isFalse();
        leases.release(stale);
        assertThat(leases.holderOf("run-1")).as("releasing a stale token does nothing").contains("node-b");
    }

    @Test
    void renewalNeedsTheTokenAndExtendsTheLeaseAlsoAfterExpiryWhenNobodyTookItOver() {
        RunLease lease = leases.tryAcquire("run-1", "node-a", TTL).orElseThrow();
        RunLease wrongToken = new RunLease("run-1", "node-a", "not-the-token", lease.expiresAt());

        assertThat(leases.renew(wrongToken, TTL)).isFalse();
        clock.advance(TTL.plusSeconds(5));
        assertThat(leases.holderOf("run-1")).isEmpty();
        assertThat(leases.renew(lease, TTL)).isTrue();

        assertThat(leases.holderOf("run-1")).contains("node-a");
        assertThat(leases.current("run-1").orElseThrow().expiresAt()).isEqualTo(clock.instant().plus(TTL));
        assertThat(leases.renew(new RunLease("run-9", "node-a", lease.token(), lease.expiresAt()), TTL)).isFalse();
    }

    @Test
    void releaseNeedsTheTokenAndFreesTheRun() {
        RunLease lease = leases.tryAcquire("run-1", "node-a", TTL).orElseThrow();

        leases.release(new RunLease("run-1", "node-a", "not-the-token", lease.expiresAt()));
        assertThat(leases.holderOf("run-1")).contains("node-a");
        leases.release(lease);

        assertThat(leases.holderOf("run-1")).isEmpty();
        assertThat(leases.current("run-1")).isEmpty();
        assertThat(leases.renew(lease, TTL)).isFalse();
        assertThat(leases.tryAcquire("run-1", "node-b", TTL)).isPresent();
    }

    @Test
    void concurrentAcquisitionsGrantExactlyOneLease() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Optional<RunLease>>> attempts = java.util.stream.IntStream.range(0, 8)
                    .<Callable<Optional<RunLease>>>mapToObj(i -> () -> leases.tryAcquire("run-c", "node-" + i, TTL))
                    .toList();
            List<Future<Optional<RunLease>>> results = pool.invokeAll(attempts);
            long granted = 0;
            for (Future<Optional<RunLease>> result : results) {
                granted += result.get().isPresent() ? 1 : 0;
            }
            assertThat(granted).isOne();
        } finally {
            pool.shutdownNow();
        }
        assertThat(leases.holderOf("run-c")).isPresent();
    }

    @Test
    void expiryIsStoredAsUtcWallClockTimeWhateverTheJvmTimeZone() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Chicago"));
            RunLease lease = leases.tryAcquire("run-1", "node-a", TTL).orElseThrow();

            LocalDateTime stored = JdbcClient.create(dataSource)
                    .sql("SELECT expires_at FROM run_leases WHERE run_id = ?").param("run-1")
                    .query((rs, n) -> rs.getObject(1, LocalDateTime.class)).single();
            assertThat(stored).isEqualTo(LocalDateTime.parse("2026-10-30T10:01:00"));
            assertThat(leases.current("run-1").orElseThrow().expiresAt()).isEqualTo(lease.expiresAt());
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void invalidArgumentsAreRejected() {
        assertThatThrownBy(() -> leases.tryAcquire("run-1", "node-a", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> leases.tryAcquire(null, "node-a", TTL)).isInstanceOf(NullPointerException.class);
    }
}
