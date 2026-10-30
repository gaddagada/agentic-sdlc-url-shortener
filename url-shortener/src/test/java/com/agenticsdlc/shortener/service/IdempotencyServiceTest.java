package com.agenticsdlc.shortener.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.agenticsdlc.shortener.config.ShortenerProperties;
import com.agenticsdlc.shortener.domain.IdempotencyRecord;
import com.agenticsdlc.shortener.repository.IdempotencyRecordRepository;
import com.agenticsdlc.shortener.service.exception.IdempotencyConflictException;
import com.agenticsdlc.shortener.service.exception.IdempotencyInProgressException;
import com.agenticsdlc.shortener.support.MutableClock;

import tools.jackson.databind.json.JsonMapper;

/**
 * Exercises the reserve/execute/complete protocol against the real schema. Test-managed transactions are disabled
 * because the service runs its own short transactions and concurrency is part of what is tested.
 */
@DataJpaTest
@Import({IdempotencyService.class, IdempotencyServiceTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class IdempotencyServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {

        @Bean
        MutableClock clock() {
            return new MutableClock(NOW);
        }

        @Bean
        JsonMapper jsonMapper() {
            return JsonMapper.builder().build();
        }
    }

    record Payload(String value, int number) {
    }

    @Autowired
    private IdempotencyService idempotency;

    @Autowired
    private IdempotencyRecordRepository records;

    @Autowired
    private MutableClock clock;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JsonMapper jsonMapper;

    private final AtomicInteger executions = new AtomicInteger();
    private String key;

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        key = UUID.randomUUID().toString();
    }

    @AfterEach
    void cleanUp() {
        records.deleteAll();
    }

    @Test
    void executesOnceAndStoresTheResponse() {
        IdempotentResult<Payload> result = idempotency.execute(key, "fp-1", 201, Payload.class, this::newPayload);

        assertThat(result).isEqualTo(new IdempotentResult<>(new Payload("created", 1), 201, false));
        IdempotencyRecord stored = records.findById(key).orElseThrow();
        assertThat(stored.isPending()).isFalse();
        assertThat(stored.getStatus()).isEqualTo(201);
        assertThat(stored.getFingerprint()).isEqualTo("fp-1");
        assertThat(stored.getBody()).contains("\"value\":\"created\"");
    }

    @Test
    void replaysTheStoredResponseForTheSameKeyAndFingerprint() {
        idempotency.execute(key, "fp-1", 201, Payload.class, this::newPayload);

        IdempotentResult<Payload> replay = idempotency.execute(key, "fp-1", 201, Payload.class, this::newPayload);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body()).isEqualTo(new Payload("created", 1));
        assertThat(executions).hasValue(1);
    }

    @Test
    void rejectsTheSameKeyWithADifferentFingerprint() {
        idempotency.execute(key, "fp-1", 201, Payload.class, this::newPayload);

        assertThatThrownBy(() -> idempotency.execute(key, "fp-2", 201, Payload.class, this::newPayload))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(executions).hasValue(1);
    }

    @Test
    void reportsAPendingReservationAsInProgress() {
        records.saveAndFlush(IdempotencyRecord.pending(key, "fp-1", NOW.minusSeconds(5)));

        assertThatThrownBy(() -> idempotency.execute(key, "fp-1", 201, Payload.class, this::newPayload))
                .isInstanceOf(IdempotencyInProgressException.class);
        assertThat(executions).hasValue(0);
    }

    @Test
    void aBulkReservationIsProtectedForItsLongerWindowAndRetryAfterReportsTheTimeLeft() {
        // 100 items: 60 s + 100 x 1 s = 160 s. Two minutes in, the bulk request is still running: a retry must not
        // take it over (it would create every link a second time), and it learns how long the protection lasts.
        IdempotentResult<Payload> first = idempotency.execute(key, "fp-1", 200, Payload.class, 100, () -> {
            clock.advance(Duration.ofMinutes(2));
            assertThatThrownBy(() -> idempotency.execute(key, "fp-1", 200, Payload.class, 100, this::newPayload))
                    .isInstanceOfSatisfying(IdempotencyInProgressException.class,
                            e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(40));
            return new Payload("bulk", 100);
        });

        assertThat(first.replayed()).isFalse();
        assertThat(executions).as("the retry never ran").hasValue(0);
        assertThat(idempotency.execute(key, "fp-1", 200, Payload.class, 100, this::newPayload).body())
                .isEqualTo(new Payload("bulk", 100));
    }

    @Test
    void aRetryDoesNotRunABulkRequestASecondTimeWhileItIsStillInFlight() {
        LinkService links = org.mockito.Mockito.mock(LinkService.class);
        BulkLinkService bulk = new BulkLinkService(links, idempotency, failure -> new ItemError(
                java.net.URI.create("urn:test:failure"), "failure", 500, failure.getMessage()),
                java.util.Optional.empty(), new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        List<CreateLinkCommand> commands = IntStream.range(0, 100)
                .mapToObj(i -> new CreateLinkCommand("https://example.com/" + i, null, null, null)).toList();
        AtomicInteger creations = new AtomicInteger();
        AtomicReference<RuntimeException> retry = new AtomicReference<>(new IllegalStateException("no retry"));
        org.mockito.Mockito.when(links.create(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.isNull())).thenAnswer(invocation -> {
                    int created = creations.incrementAndGet();
                    if (created == 100) {
                        // the last item finishes two minutes after the request started; the client retried meanwhile
                        clock.advance(Duration.ofMinutes(2));
                        try {
                            bulk.createAll(commands, key, "client");
                            retry.set(null);
                        } catch (RuntimeException rejected) {
                            retry.set(rejected);
                        }
                    }
                    return new CreatedLink(new LinkView("c" + created, "https://sho.rt/c" + created,
                            "https://example.com/", com.agenticsdlc.shortener.domain.LinkStatus.ACTIVE, NOW, null, 0),
                            false);
                });

        BulkCreationResult result = bulk.createAll(commands, key, "client");

        assertThat(result.creation().createdCount()).isEqualTo(100);
        assertThat(retry.get()).as("the retry is told to wait instead of taking the reservation over")
                .isInstanceOf(IdempotencyInProgressException.class);
        assertThat(creations).as("every link was created exactly once").hasValue(100);
    }

    @Test
    void aReservationIsTakenOverOnlyAfterTheWindowItRecorded() {
        records.saveAndFlush(IdempotencyRecord.pending(key, "fp-1", NOW.minusSeconds(150), Duration.ofSeconds(160)));
        assertThatThrownBy(() -> idempotency.execute(key, "fp-1", 200, Payload.class, this::newPayload))
                .as("a retry is judged by the window of the request in flight, not by its own")
                .isInstanceOfSatisfying(IdempotencyInProgressException.class,
                        e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(10));

        clock.advance(Duration.ofSeconds(11));
        assertThat(idempotency.execute(key, "fp-1", 200, Payload.class, this::newPayload).replayed()).isFalse();
        assertThat(executions).hasValue(1);

        String legacy = UUID.randomUUID().toString();
        records.saveAndFlush(IdempotencyRecord.pending(legacy, "fp-1", clock.instant().minusSeconds(45)));
        assertThatThrownBy(() -> idempotency.execute(legacy, "fp-1", 200, Payload.class, this::newPayload))
                .as("a reservation without a recorded window keeps the base window of 60 s")
                .isInstanceOfSatisfying(IdempotencyInProgressException.class,
                        e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(15));
    }

    @Test
    void theTakeoverWindowsAreConfigurable() {
        IdempotencyService custom = new IdempotencyService(records, transactionManager, jsonMapper, clock,
                new ShortenerProperties.Idempotency(Duration.ofSeconds(10), Duration.ofMillis(500)));
        IdempotentResult<Payload> first = custom.execute(key, "fp-1", 200, Payload.class, 4, () -> {
            assertThatThrownBy(() -> custom.execute(key, "fp-1", 200, Payload.class, 4, this::newPayload))
                    .isInstanceOfSatisfying(IdempotencyInProgressException.class,
                            e -> assertThat(e.getRetryAfterSeconds()).as("10 s + 4 x 0.5 s").isEqualTo(12));
            return new Payload("custom", 4);
        });
        assertThat(first.body()).isEqualTo(new Payload("custom", 4));
    }

    @Test
    void takesOverAnAbandonedReservation() {
        records.saveAndFlush(IdempotencyRecord.pending(key, "fp-1", NOW.minus(Duration.ofMinutes(2))));

        IdempotentResult<Payload> result = idempotency.execute(key, "fp-1", 201, Payload.class, this::newPayload);

        assertThat(result.replayed()).isFalse();
        assertThat(executions).hasValue(1);
    }

    @Test
    void forgetsResponsesAfterTwentyFourHours() {
        idempotency.execute(key, "fp-1", 201, Payload.class, this::newPayload);
        clock.advance(Duration.ofHours(24).plusSeconds(1));

        IdempotentResult<Payload> later = idempotency.execute(key, "fp-2", 201, Payload.class, this::newPayload);

        assertThat(later.replayed()).isFalse();
        assertThat(executions).hasValue(2);
        assertThat(records.findById(key).orElseThrow().getFingerprint()).isEqualTo("fp-2");
    }

    @Test
    void releasesTheKeyWhenTheActionFails() {
        assertThatThrownBy(() -> idempotency.execute(key, "fp-1", 201, Payload.class, () -> {
            throw new IllegalStateException("boom");
        })).hasMessage("boom");
        assertThat(records.findById(key)).isEmpty();

        IdempotentResult<Payload> retry = idempotency.execute(key, "fp-1", 201, Payload.class, this::newPayload);
        assertThat(retry.replayed()).isFalse();
    }

    @Test
    void rejectsAConcurrentDuplicateWhileTheFirstRequestIsInFlight() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CompletableFuture<IdempotentResult<Payload>> first = CompletableFuture.supplyAsync(() ->
                idempotency.execute(key, "fp-1", 201, Payload.class, () -> {
                    firstStarted.countDown();
                    await(releaseFirst);
                    return newPayload();
                }));
        assertThat(firstStarted.await(10, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> idempotency.execute(key, "fp-1", 201, Payload.class, this::newPayload))
                .isInstanceOf(IdempotencyInProgressException.class);

        releaseFirst.countDown();
        assertThat(first.get(10, TimeUnit.SECONDS).replayed()).isFalse();
        assertThat(idempotency.execute(key, "fp-1", 201, Payload.class, this::newPayload).replayed()).isTrue();
        assertThat(executions).hasValue(1);
    }

    @Test
    void aRequestThatWasTakenOverDoesNotCompleteTheReservationOfTheRequestThatTookOver() throws Exception {
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        AtomicReference<CompletableFuture<IdempotentResult<Payload>>> second = new AtomicReference<>();

        // The first request stalls past the pending timeout; a retry takes the key over and is still running when
        // the first request finally completes.
        IdempotentResult<Payload> first = idempotency.execute(key, "fp-1", 201, Payload.class, () -> {
            clock.advance(Duration.ofMinutes(2));
            second.set(CompletableFuture.supplyAsync(() ->
                    idempotency.execute(key, "fp-1", 201, Payload.class, () -> {
                        secondStarted.countDown();
                        await(releaseSecond);
                        return new Payload("second", 2);
                    })));
            await(secondStarted);
            return new Payload("first", 1);
        });

        assertThat(first.body()).isEqualTo(new Payload("first", 1));
        assertThat(records.findById(key).orElseThrow().isPending()).as("the retry's reservation is untouched")
                .isTrue();
        assertThatThrownBy(() -> idempotency.execute(key, "fp-1", 201, Payload.class, this::newPayload))
                .isInstanceOf(IdempotencyInProgressException.class);

        releaseSecond.countDown();
        assertThat(second.get().get(10, TimeUnit.SECONDS).body()).isEqualTo(new Payload("second", 2));
        IdempotentResult<Payload> replay = idempotency.execute(key, "fp-1", 201, Payload.class, this::newPayload);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.body()).as("replays what the request holding the reservation answered")
                .isEqualTo(new Payload("second", 2));
        assertThat(executions).hasValue(0);
    }

    @Test
    void aFailingRequestThatWasTakenOverDoesNotReleaseTheReservationOfTheRequestThatTookOver() throws Exception {
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        AtomicReference<CompletableFuture<IdempotentResult<Payload>>> second = new AtomicReference<>();

        assertThatThrownBy(() -> idempotency.execute(key, "fp-1", 201, Payload.class, () -> {
            clock.advance(Duration.ofMinutes(2));
            second.set(CompletableFuture.supplyAsync(() ->
                    idempotency.execute(key, "fp-1", 201, Payload.class, () -> {
                        secondStarted.countDown();
                        await(releaseSecond);
                        return new Payload("second", 2);
                    })));
            await(secondStarted);
            throw new IllegalStateException("first request failed late");
        })).hasMessage("first request failed late");

        assertThat(records.findById(key)).as("the retry's reservation is not released").isPresent();
        assertThatThrownBy(() -> idempotency.execute(key, "fp-1", 201, Payload.class, this::newPayload))
                .isInstanceOf(IdempotencyInProgressException.class);

        releaseSecond.countDown();
        assertThat(second.get().get(10, TimeUnit.SECONDS).replayed()).isFalse();
        assertThat(idempotency.execute(key, "fp-1", 201, Payload.class, this::newPayload).body())
                .isEqualTo(new Payload("second", 2));
        assertThat(executions).hasValue(0);
    }

    @Test
    void purgesRecordsOlderThanTheRetentionWindow() {
        idempotency.execute(key, "fp-1", 201, Payload.class, this::newPayload);
        String recentKey = UUID.randomUUID().toString();
        clock.advance(Duration.ofHours(23));
        idempotency.execute(recentKey, "fp-1", 201, Payload.class, this::newPayload);
        clock.advance(Duration.ofHours(2));

        idempotency.purgeExpired();

        assertThat(records.findById(key)).isEmpty();
        assertThat(records.findById(recentKey)).isPresent();
    }

    @Test
    void fingerprintsAreStableAndScoped() {
        Payload payload = new Payload("x", 1);

        String fingerprint = idempotency.fingerprint("create-link", payload);

        assertThat(fingerprint).matches("^[0-9a-f]{64}$");
        assertThat(idempotency.fingerprint("create-link", new Payload("x", 1))).isEqualTo(fingerprint);
        assertThat(idempotency.fingerprint("other-operation", payload)).isNotEqualTo(fingerprint);
        assertThat(idempotency.fingerprint("create-link", new Payload("x", 2))).isNotEqualTo(fingerprint);
    }

    private Payload newPayload() {
        return new Payload("created", executions.incrementAndGet());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
