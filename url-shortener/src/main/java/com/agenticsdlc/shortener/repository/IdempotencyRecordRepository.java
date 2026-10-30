package com.agenticsdlc.shortener.repository;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.agenticsdlc.shortener.domain.IdempotencyRecord;

/**
 * Persistence for {@link IdempotencyRecord}. Mutations are bulk statements so that they are atomic and never fail
 * with optimistic-locking errors when two requests race on the same key.
 */
public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, String> {

    /**
     * Deletes the record of a key regardless of its state (used to recycle expired or abandoned keys).
     *
     * @param key idempotency key
     * @return number of deleted rows
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from IdempotencyRecord r where r.idempotencyKey = :key")
    int deleteByKey(@Param("key") String key);

    /**
     * Completes a pending record with the response to replay.
     *
     * @param key     idempotency key
     * @param status  response status
     * @param body    response body (JSON)
     * @param pending the pending status marker
     * @return number of updated rows (0 if the record is no longer pending)
     */
    @Transactional
    @Modifying
    @Query("""
            update IdempotencyRecord r set r.status = :status, r.body = :body
            where r.idempotencyKey = :key and r.status = :pending
            """)
    int complete(@Param("key") String key, @Param("status") int status, @Param("body") String body,
                 @Param("pending") int pending);

    /**
     * Completes one specific pending reservation with the response to replay: the one created at {@code reservedAt}.
     * A request whose reservation was taken over (abandoned and re-reserved by a retry) therefore cannot overwrite the
     * newer reservation.
     *
     * @param key        idempotency key
     * @param status     response status
     * @param body       response body (JSON)
     * @param pending    the pending status marker
     * @param reservedAt creation instant of the reservation, exactly as stored
     * @return number of updated rows (0 if that reservation is no longer pending or was replaced)
     */
    @Transactional
    @Modifying
    @Query("""
            update IdempotencyRecord r set r.status = :status, r.body = :body
            where r.idempotencyKey = :key and r.status = :pending and r.createdAt = :reservedAt
            """)
    int completeReservation(@Param("key") String key, @Param("status") int status, @Param("body") String body,
                            @Param("pending") int pending, @Param("reservedAt") Instant reservedAt);

    /**
     * Releases a reservation whose request failed, so that the client can retry with the same key.
     *
     * @param key     idempotency key
     * @param pending the pending status marker
     * @return number of deleted rows
     */
    @Transactional
    @Modifying
    @Query("delete from IdempotencyRecord r where r.idempotencyKey = :key and r.status = :pending")
    int deletePending(@Param("key") String key, @Param("pending") int pending);

    /**
     * Releases one specific pending reservation (the one created at {@code reservedAt}) whose request failed. A request
     * whose reservation was taken over therefore cannot release the newer reservation of the retry.
     *
     * @param key        idempotency key
     * @param pending    the pending status marker
     * @param reservedAt creation instant of the reservation, exactly as stored
     * @return number of deleted rows
     */
    @Transactional
    @Modifying
    @Query("""
            delete from IdempotencyRecord r
            where r.idempotencyKey = :key and r.status = :pending and r.createdAt = :reservedAt
            """)
    int deleteReservation(@Param("key") String key, @Param("pending") int pending,
                          @Param("reservedAt") Instant reservedAt);

    /**
     * Purges records created before {@code cutoff}.
     *
     * @param cutoff retention boundary
     * @return number of deleted rows
     */
    @Transactional
    @Modifying
    @Query("delete from IdempotencyRecord r where r.createdAt < :cutoff")
    int deleteCreatedBefore(@Param("cutoff") Instant cutoff);
}
