package com.agenticsdlc.orchestrator.app.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.agenticsdlc.orchestrator.core.port.RunLease;
import com.agenticsdlc.orchestrator.core.port.RunLeaseProvider;

/**
 * {@link RunLeaseProvider} on the {@code run_leases} table (one row per leased run), so that every engine node
 * using the same database sees the same leases. Every statement is atomic on H2 and PostgreSQL without
 * vendor-specific syntax:
 *
 * <ul>
 *   <li>acquire: {@code INSERT} when the run has no row; when its row has expired, a compare-and-set
 *       {@code UPDATE ... WHERE run_id = ? AND token = <expired token> AND expires_at <= <now>} hands it to the new
 *       owner with a new token. A concurrent insert of the same run fails on the primary key and a concurrent
 *       takeover changes the token, so exactly one node wins; the loser gets no lease. A valid row (of any owner,
 *       this one included) is never overwritten: leases are not re-entrant;</li>
 *   <li>renew: {@code UPDATE ... SET expires_at = ? WHERE run_id = ? AND token = ?} (also after expiry, as long as
 *       nobody took the run over);</li>
 *   <li>release: {@code DELETE ... WHERE run_id = ? AND token = ?};</li>
 *   <li>reclaim (an owner that lost its token, for example a node restarted with a fixed node id): one conditional
 *       {@code UPDATE ... SET owner_id = ?, token = <new>, expires_at = ? WHERE run_id = ? AND (owner_id = ? OR
 *       expires_at <= <now>)}; a valid lease of another owner is never taken, the previous token stops holding the
 *       run, and a run without a row is not inserted (the caller acquires it normally);</li>
 *   <li>holder: {@code SELECT owner_id ... WHERE run_id = ? AND expires_at > <now>}.</li>
 * </ul>
 *
 * <p>Each call runs in its own short transaction (propagation {@code REQUIRES_NEW}), never inside the engine's
 * commit transaction, so a lease change is visible to other nodes as soon as the call returns and a failed run
 * commit cannot roll a lease back. "Now" is the given clock (the engine clock) truncated to milliseconds; nodes
 * judge expiry with their own clocks, so clock skew between nodes must stay well below the lease TTL. Timestamps are
 * bound as UTC wall-clock {@link LocalDateTime} values, like every TIMESTAMP column of the schema, so the result does
 * not depend on the JVM time zone.</p>
 */
public final class JdbcRunLeaseProvider implements RunLeaseProvider {

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /**
     * Creates the provider.
     *
     * @param dataSource the control plane database (with the {@code run_leases} table of migration V2)
     * @param transactionManager transaction manager of that database
     * @param clock clock judging expiry (the engine clock)
     */
    public JdbcRunLeaseProvider(DataSource dataSource, PlatformTransactionManager transactionManager, Clock clock) {
        this.jdbc = JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource"));
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Optional<RunLease> tryAcquire(String runId, String ownerId, Duration ttl) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(ownerId, "ownerId");
        requirePositive(ttl);
        Instant now = now();
        RunLease lease = new RunLease(runId, ownerId, UUID.randomUUID().toString(), now.plus(ttl));
        try {
            Boolean granted = transactions.execute(status -> grant(lease, now));
            return Boolean.TRUE.equals(granted) ? Optional.of(lease) : Optional.empty();
        } catch (DataIntegrityViolationException | ConcurrencyFailureException e) {
            // another node inserted (or took over) the lease concurrently: it won
            return Optional.empty();
        } catch (DataAccessException e) {
            if (holderOf(runId).isPresent()) {
                return Optional.empty();
            }
            throw e;
        }
    }

    private boolean grant(RunLease lease, Instant now) {
        Optional<Row> current = jdbc.sql("SELECT token, expires_at FROM run_leases WHERE run_id = ?")
                .param(lease.runId()).query(JdbcRunLeaseProvider::row).optional();
        if (current.isEmpty()) {
            jdbc.sql("INSERT INTO run_leases (run_id, owner_id, token, expires_at) VALUES (?, ?, ?, ?)")
                    .params(lease.runId(), lease.ownerId(), lease.token(), utc(lease.expiresAt())).update();
            return true;
        }
        if (now.isBefore(current.get().expiresAt())) {
            return false;
        }
        int updated = jdbc.sql("UPDATE run_leases SET owner_id = ?, token = ?, expires_at = ? "
                        + "WHERE run_id = ? AND token = ? AND expires_at <= ?")
                .params(lease.ownerId(), lease.token(), utc(lease.expiresAt()), lease.runId(), current.get().token(),
                        utc(now))
                .update();
        return updated == 1;
    }

    /**
     * Reclaims a run's lease for an owner that no longer knows its token: one conditional {@code UPDATE} gives the
     * row a new token and expiry when it belongs to {@code ownerId} (valid or expired) or has expired (any previous
     * owner). A valid lease of another owner is left alone, and a run without a row gets none (no insert:
     * {@link #tryAcquire} handles free runs). Runs in its own short transaction, like every statement of this
     * provider.
     *
     * @param runId run id
     * @param ownerId id of the reclaiming node
     * @param ttl lease duration (positive)
     * @return the new lease when the row was updated, else empty
     */
    @Override
    public Optional<RunLease> reclaim(String runId, String ownerId, Duration ttl) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(ownerId, "ownerId");
        requirePositive(ttl);
        Instant now = now();
        RunLease lease = new RunLease(runId, ownerId, UUID.randomUUID().toString(), now.plus(ttl));
        Integer updated = transactions.execute(status -> jdbc
                .sql("UPDATE run_leases SET owner_id = ?, token = ?, expires_at = ? "
                        + "WHERE run_id = ? AND (owner_id = ? OR expires_at <= ?)")
                .params(lease.ownerId(), lease.token(), utc(lease.expiresAt()), runId, ownerId, utc(now))
                .update());
        return updated != null && updated == 1 ? Optional.of(lease) : Optional.empty();
    }

    @Override
    public boolean renew(RunLease lease, Duration ttl) {
        Objects.requireNonNull(lease, "lease");
        requirePositive(ttl);
        Instant expiresAt = now().plus(ttl);
        Integer updated = transactions.execute(status -> jdbc
                .sql("UPDATE run_leases SET expires_at = ? WHERE run_id = ? AND token = ?")
                .params(utc(expiresAt), lease.runId(), lease.token()).update());
        return updated != null && updated == 1;
    }

    @Override
    public void release(RunLease lease) {
        Objects.requireNonNull(lease, "lease");
        transactions.executeWithoutResult(status -> jdbc
                .sql("DELETE FROM run_leases WHERE run_id = ? AND token = ?")
                .params(lease.runId(), lease.token()).update());
    }

    @Override
    public Optional<String> holderOf(String runId) {
        Objects.requireNonNull(runId, "runId");
        Instant now = now();
        Optional<String> owner = transactions.execute(status -> jdbc
                .sql("SELECT owner_id FROM run_leases WHERE run_id = ? AND expires_at > ?")
                .params(runId, utc(now)).query(String.class).optional());
        return owner == null ? Optional.empty() : owner;
    }

    /**
     * Returns the run's lease row, valid or expired (diagnostics and tests).
     *
     * @param runId run id
     * @return the lease as stored, or empty when the run has no row
     */
    public Optional<RunLease> current(String runId) {
        Objects.requireNonNull(runId, "runId");
        Optional<RunLease> lease = transactions.execute(status -> jdbc
                .sql("SELECT run_id, owner_id, token, expires_at FROM run_leases WHERE run_id = ?")
                .param(runId)
                .query((rs, n) -> new RunLease(rs.getString(1), rs.getString(2), rs.getString(3),
                        instant(rs, 4)))
                .optional());
        return lease == null ? Optional.empty() : lease;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static Row row(ResultSet rs, int rowNum) throws SQLException {
        return new Row(rs.getString(1), instant(rs, 2));
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return value.toInstant(ZoneOffset.UTC);
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static void requirePositive(Duration ttl) {
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("lease ttl must be positive: " + ttl);
        }
    }

    @Override
    public String toString() {
        return "JdbcRunLeaseProvider[table=run_leases]";
    }

    /** The token and expiry of a stored lease. */
    private record Row(String token, Instant expiresAt) {
    }
}
