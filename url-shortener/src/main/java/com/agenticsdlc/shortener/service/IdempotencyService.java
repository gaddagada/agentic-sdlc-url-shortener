package com.agenticsdlc.shortener.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
 * Records are replayable for {@value #RETENTION_HOURS} hours. A pending record older than one minute is treated as
 * abandoned (crashed request) and may be taken over. Expired records are purged hourly.
 */
@Service
public class IdempotencyService {

    static final long RETENTION_HOURS = 24;
    static final Duration RETENTION = Duration.ofHours(RETENTION_HOURS);
    static final Duration PENDING_TIMEOUT = Duration.ofMinutes(1);

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyRecordRepository records;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param records            idempotency record repository
     * @param transactionManager transaction manager for the reservation transaction
     * @param objectMapper       serializes request fingerprints and response bodies
     * @param clock              time source
     */
    public IdempotencyService(IdempotencyRecordRepository records, PlatformTransactionManager transactionManager,
                              ObjectMapper objectMapper, Clock clock) {
        this.records = records;
        this.transactions = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
        this.clock = clock;
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
        Optional<IdempotencyRecord> stored = reserve(key, fingerprint);
        if (stored.isPresent()) {
            IdempotencyRecord record = stored.get();
            log.debug("idempotent replay status={}", record.getStatus());
            return new IdempotentResult<>(objectMapper.readValue(record.getBody(), bodyType), record.getStatus(), true);
        }
        T body;
        try {
            body = action.get();
        } catch (RuntimeException failure) {
            release(key, failure);
            throw failure;
        }
        complete(key, status, body);
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

    private Optional<IdempotencyRecord> reserve(String key, String fingerprint) {
        try {
            return reserveOnce(key, fingerprint);
        } catch (DataIntegrityViolationException concurrentReservation) {
            // Another request inserted the key between our read and our insert: its record decides the outcome.
            return reserveOnce(key, fingerprint);
        }
    }

    private Optional<IdempotencyRecord> reserveOnce(String key, String fingerprint) {
        return Objects.requireNonNull(transactions.execute(tx -> reserveInTransaction(key, fingerprint)));
    }

    private Optional<IdempotencyRecord> reserveInTransaction(String key, String fingerprint) {
        Instant now = clock.instant();
        Optional<IdempotencyRecord> existing = records.findById(key);
        if (existing.isPresent()) {
            IdempotencyRecord record = existing.get();
            if (isLive(record, now)) {
                if (!record.getFingerprint().equals(fingerprint)) {
                    throw new IdempotencyConflictException();
                }
                if (record.isPending()) {
                    throw new IdempotencyInProgressException();
                }
                return Optional.of(record);
            }
            records.deleteByKey(key);
        }
        records.saveAndFlush(IdempotencyRecord.pending(key, fingerprint, now));
        return Optional.empty();
    }

    private static boolean isLive(IdempotencyRecord record, Instant now) {
        boolean expired = record.isCreatedBefore(now.minus(RETENTION));
        boolean abandoned = record.isPending() && record.isCreatedBefore(now.minus(PENDING_TIMEOUT));
        return !expired && !abandoned;
    }

    private void complete(String key, int status, Object body) {
        int updated = records.complete(key, status, objectMapper.writeValueAsString(body),
                IdempotencyRecord.PENDING_STATUS);
        if (updated == 0) {
            log.warn("idempotency reservation was no longer pending at completion; the response is not replayable");
        }
    }

    private void release(String key, RuntimeException cause) {
        try {
            records.deletePending(key, IdempotencyRecord.PENDING_STATUS);
        } catch (RuntimeException releaseFailure) {
            cause.addSuppressed(releaseFailure);
            log.warn("could not release idempotency reservation; it expires after {}", PENDING_TIMEOUT);
        }
    }
}
