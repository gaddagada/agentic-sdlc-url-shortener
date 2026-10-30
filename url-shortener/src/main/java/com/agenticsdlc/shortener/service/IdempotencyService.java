package com.agenticsdlc.shortener.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.agenticsdlc.shortener.config.ShortenerProperties;
import com.agenticsdlc.shortener.domain.IdempotencyRecord;
import com.agenticsdlc.shortener.repository.IdempotencyRecordRepository;
import com.agenticsdlc.shortener.service.exception.IdempotencyConflictException;
import com.agenticsdlc.shortener.service.exception.IdempotencyInProgressException;

import tools.jackson.databind.ObjectMapper;

/**
 * Makes an operation safe to retry with a client-supplied {@code Idempotency-Key}.
 *
 * <p>Protocol (reserve, execute, complete):
 * <ol>
 *   <li><b>reserve</b>: in a short transaction, look up the key. A live completed record with the same fingerprint
 *       is replayed; a different fingerprint is rejected ({@link IdempotencyConflictException}); a live pending
 *       record means a concurrent duplicate ({@link IdempotencyInProgressException}). Otherwise a pending record is
 *       inserted. Two racing requests cannot both reserve: the second insert fails on the primary key and the
 *       request re-evaluates against the winner's record.</li>
 *   <li><b>execute</b> the action outside that transaction. On failure the reservation is released so the client
 *       can retry with the same key (only successful responses are stored).</li>
 *   <li><b>complete</b>: store the response status and JSON body.</li>
 * </ol>
 * Records are replayable for {@value #RETENTION_HOURS} hours. A pending record is protected for its takeover window
 * ({@link ShortenerProperties.Idempotency}: {@code pending-takeover-after}, default 60 s, plus
 * {@code pending-takeover-per-item}, default 1 s, per item of a bulk request, at most 10 minutes); the window is
 * stored with the reservation. While it lasts, a retry gets {@link IdempotencyInProgressException} with the seconds
 * left; after it the request is presumed crashed and a retry takes the reservation over. Completion and release act
 * only on the request's own reservation (identified by its creation instant), so a request that was taken over can
 * neither overwrite the response of the request that took over nor release its reservation. Expired records are
 * purged hourly.
 */
@Service
public class IdempotencyService {

    static final long RETENTION_HOURS = 24;
    static final Duration RETENTION = Duration.ofHours(RETENTION_HOURS);
    /** Takeover window of a single request with the default settings (and of reservations that recorded none). */
    static final Duration PENDING_TIMEOUT = Duration.ofMinutes(1);

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyRecordRepository records;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ShortenerProperties.Idempotency settings;

    /**
     * Creates the service with the default takeover windows ({@link ShortenerProperties.Idempotency#defaults()}).
     *
     * @param records            idempotency record repository
     * @param transactionManager transaction manager for the reservation transaction
     * @param objectMapper       serializes request fingerprints and response bodies
     * @param clock              time source
     */
    public IdempotencyService(IdempotencyRecordRepository records, PlatformTransactionManager transactionManager,
                              ObjectMapper objectMapper, Clock clock) {
        this(records, transactionManager, objectMapper, clock, ShortenerProperties.Idempotency.defaults());
    }

    /**
     * Creates the service.
     *
     * @param records            idempotency record repository
     * @param transactionManager transaction manager for the reservation transaction
     * @param objectMapper       serializes request fingerprints and response bodies
     * @param clock              time source
     * @param settings           takeover windows of pending reservations
     */
    public IdempotencyService(IdempotencyRecordRepository records, PlatformTransactionManager transactionManager,
                              ObjectMapper objectMapper, Clock clock, ShortenerProperties.Idempotency settings) {
        this.records = records;
        this.transactions = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /**
     * Creates the service with the {@code shortener.idempotency.*} settings, or the defaults when no
     * {@link ShortenerProperties} are available (slice tests).
     *
     * @param records            idempotency record repository
     * @param transactionManager transaction manager for the reservation transaction
     * @param objectMapper       serializes request fingerprints and response bodies
     * @param clock              time source
     * @param properties         the shortener properties, if any
     */
    @Autowired
    IdempotencyService(IdempotencyRecordRepository records, PlatformTransactionManager transactionManager,
                       ObjectMapper objectMapper, Clock clock, ObjectProvider<ShortenerProperties> properties) {
        this(records, transactionManager, objectMapper, clock, properties.stream().findFirst()
                .map(ShortenerProperties::idempotency).orElseGet(ShortenerProperties.Idempotency::defaults));
    }

    /**
     * Computes the request fingerprint: SHA-256 hex of the operation scope and the canonical JSON of the request.
     *
     * @param scope   operation name, so that the same payload sent to different operations never matches
     * @param request request payload (a record)
     * @return 64 character lower-case hex digest
     */
    public String fingerprint(String scope, Object request) {
        return Sha256.hex(scope + '\n' + objectMapper.writeValueAsString(request));
    }

    /**
     * Executes {@code action} at most once per key and fingerprint within the retention window.
     *
     * @param key         client supplied idempotency key
     * @param fingerprint fingerprint of the request (see {@link #fingerprint})
     * @param status      response status to record for a successful execution
     * @param bodyType    type to deserialize a replayed body into
     * @param action      the operation to protect
     * @param <T>         response body type, must round-trip through JSON
     * @return the fresh or replayed result
     * @throws IdempotencyConflictException   when the key was used with a different fingerprint
     * @throws IdempotencyInProgressException when the key is reserved by a request still in flight
     */
    public <T> IdempotentResult<T> execute(String key, String fingerprint, int status, Class<T> bodyType,
                                           Supplier<T> action) {
        return execute(key, fingerprint, status, bodyType, 0, action);
    }

    /**
     * Executes {@code action} at most once per key and fingerprint within the retention window, protecting the
     * reservation of a bulk request of {@code items} items for its longer takeover window
     * ({@link ShortenerProperties.Idempotency#takeoverWindow(int)}).
     *
     * @param key         client supplied idempotency key
     * @param fingerprint fingerprint of the request (see {@link #fingerprint})
     * @param status      response status to record for a successful execution
     * @param bodyType    type to deserialize a replayed body into
     * @param items       items of a bulk request, 0 for a single request
     * @param action      the operation to protect
     * @param <T>         response body type, must round-trip through JSON
     * @return the fresh or replayed result
     * @throws IdempotencyConflictException   when the key was used with a different fingerprint
     * @throws IdempotencyInProgressException when the key is reserved by a request still in flight
     */
    public <T> IdempotentResult<T> execute(String key, String fingerprint, int status, Class<T> bodyType, int items,
                                           Supplier<T> action) {
        Reservation reservation = reserve(key, fingerprint, settings.takeoverWindow(items));
        IdempotencyRecord record = reservation.replay();
        if (record != null) {
            log.debug("idempotent replay status={}", record.getStatus());
            return new IdempotentResult<>(objectMapper.readValue(record.getBody(), bodyType), record.getStatus(), true);
        }
        Instant reservedAt = Objects.requireNonNull(reservation.reservedAt());
        T body;
        try {
            body = action.get();
        } catch (RuntimeException failure) {
            release(key, reservedAt, failure);
            throw failure;
        }
        complete(key, reservedAt, status, body);
        return new IdempotentResult<>(body, status, false);
    }

    /** Hourly housekeeping: deletes records older than the retention window. */
    @Scheduled(initialDelay = 5, fixedDelay = 60, timeUnit = TimeUnit.MINUTES)
    public void purgeExpired() {
        int purged = records.deleteCreatedBefore(clock.instant().minus(RETENTION));
        if (purged > 0) {
            log.info("purged expired idempotency records count={}", purged);
        }
    }

    private Reservation reserve(String key, String fingerprint, Duration takeoverWindow) {
        try {
            return reserveOnce(key, fingerprint, takeoverWindow);
        } catch (DataIntegrityViolationException concurrentReservation) {
            // Another request inserted the key between our read and our insert: its record decides the outcome.
            return reserveOnce(key, fingerprint, takeoverWindow);
        }
    }

    private Reservation reserveOnce(String key, String fingerprint, Duration takeoverWindow) {
        return Objects.requireNonNull(transactions.execute(tx -> reserveInTransaction(key, fingerprint,
                takeoverWindow)));
    }

    private Reservation reserveInTransaction(String key, String fingerprint, Duration takeoverWindow) {
        Instant now = clock.instant();
        Optional<IdempotencyRecord> existing = records.findById(key);
        if (existing.isPresent()) {
            IdempotencyRecord record = existing.get();
            if (isLive(record, now)) {
                if (!record.getFingerprint().equals(fingerprint)) {
                    throw new IdempotencyConflictException();
                }
                if (record.isPending()) {
                    throw new IdempotencyInProgressException(secondsUntilTakeover(record, now));
                }
                return new Reservation(record, null);
            }
            records.deleteByKey(key);
        }
        // Millisecond precision survives the TIMESTAMP round trip exactly, so the reservation can be matched later.
        Instant reservedAt = now.truncatedTo(ChronoUnit.MILLIS);
        records.saveAndFlush(IdempotencyRecord.pending(key, fingerprint, reservedAt, takeoverWindow));
        return new Reservation(null, reservedAt);
    }

    private boolean isLive(IdempotencyRecord record, Instant now) {
        boolean expired = record.isCreatedBefore(now.minus(RETENTION));
        boolean abandoned = record.isPending() && record.isCreatedBefore(now.minus(takeoverWindowOf(record)));
        return !expired && !abandoned;
    }

    /** The takeover window recorded with a pending reservation (the base window for one that recorded none). */
    private Duration takeoverWindowOf(IdempotencyRecord record) {
        return record.pendingTakeoverWindow(settings.pendingTakeoverAfter());
    }

    /** Whole seconds, rounded up, until a pending reservation may be taken over. */
    private long secondsUntilTakeover(IdempotencyRecord record, Instant now) {
        Duration left = Duration.between(now, record.getCreatedAt().plus(takeoverWindowOf(record)));
        return Math.max(1L, (left.toMillis() + 999L) / 1000L);
    }

    private void complete(String key, Instant reservedAt, int status, Object body) {
        int updated = records.completeReservation(key, status, objectMapper.writeValueAsString(body),
                IdempotencyRecord.PENDING_STATUS, reservedAt);
        if (updated == 0) {
            log.warn("idempotency reservation was no longer pending at completion (taken over after its takeover "
                    + "window or purged); the response is not replayable");
        }
    }

    private void release(String key, Instant reservedAt, RuntimeException cause) {
        try {
            records.deleteReservation(key, IdempotencyRecord.PENDING_STATUS, reservedAt);
        } catch (RuntimeException releaseFailure) {
            cause.addSuppressed(releaseFailure);
            log.warn("could not release idempotency reservation; a retry takes it over after its takeover window");
        }
    }

    /**
     * Outcome of a reservation attempt: either a completed record to replay, or the creation instant of this request's
     * new pending reservation.
     *
     * @param replay     the completed record to replay, or null
     * @param reservedAt creation instant of the new reservation (as stored), or null when replaying
     */
    private record Reservation(@Nullable IdempotencyRecord replay, @Nullable Instant reservedAt) {
    }
}
