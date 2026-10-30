package com.agenticsdlc.shortener.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;

import com.agenticsdlc.shortener.domain.LinkStatus;
import com.agenticsdlc.shortener.service.BulkCreation.Item;
import com.agenticsdlc.shortener.service.exception.BatchTooLargeException;
import com.agenticsdlc.shortener.service.exception.IdempotencyConflictException;
import com.agenticsdlc.shortener.service.exception.RateLimitExceededException;
import com.agenticsdlc.shortener.service.exception.UrlRejectedException;
import com.agenticsdlc.shortener.support.RecordingRateLimiter;

class BulkLinkServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    private static final String CLIENT = "192.0.2.1";

    private final LinkService linkService = mock(LinkService.class);
    private final IdempotencyService idempotency = mock(IdempotencyService.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    /** Encodes the failure class and message, so tests can see which exception each item failed with. */
    private final ItemErrorMapper errorMapper = failure -> new ItemError(
            URI.create("urn:test:" + failure.getClass().getSimpleName()), "title", 999, failure.getMessage());

    @Test
    void createsEveryItemIndependentlyAndReportsFailuresInPlace() {
        when(linkService.create(command("https://example.com/a"), null)).thenReturn(created("aaaa"));
        when(linkService.create(command("http://10.0.0.1/"), null)).thenThrow(new UrlRejectedException("private"));
        when(linkService.create(command("https://example.com/c"), null)).thenReturn(created("cccc"));

        BulkCreationResult result = service(null).createAll(List.of(command("https://example.com/a"),
                command("http://10.0.0.1/"), command("https://example.com/c")), null, CLIENT);

        assertThat(result.replayed()).isFalse();
        assertThat(result.rateLimit()).isNull();
        List<Item> items = result.creation().items();
        assertThat(items).extracting(Item::index).containsExactly(0, 1, 2);
        assertThat(items.get(0).link()).isEqualTo(view("aaaa"));
        assertThat(items.get(1).error()).isEqualTo(new ItemError(URI.create("urn:test:UrlRejectedException"),
                "title", 999, "private"));
        assertThat(items.get(2).link()).isEqualTo(view("cccc"));
        assertThat(result.creation().createdCount()).isEqualTo(2);
        assertThat(result.creation().failedCount()).isEqualTo(1);
        assertThat(meters.counter(BulkLinkService.ITEMS_METRIC, "outcome", "created").count()).isEqualTo(2);
        assertThat(meters.counter(BulkLinkService.ITEMS_METRIC, "outcome", "failed").count()).isEqualTo(1);
    }

    @Test
    void anUnexpectedFailureOfOneItemDoesNotStopTheOthers() {
        when(linkService.create(command("https://example.com/a"), null))
                .thenThrow(new IllegalStateException("connection reset"));
        when(linkService.create(command("https://example.com/b"), null)).thenReturn(created("bbbb"));

        BulkCreation creation = service(null).createAll(List.of(command("https://example.com/a"),
                command("https://example.com/b")), null, CLIENT).creation();

        assertThat(creation.items().get(0).error().type()).isEqualTo(URI.create("urn:test:IllegalStateException"));
        assertThat(creation.items().get(1).succeeded()).isTrue();
    }

    @Test
    void laterItemsRepeatingAnAliasFailUpFrontWithoutBeingAttempted() {
        CreateLinkCommand first = aliased("https://example.com/1", "promo");
        CreateLinkCommand other = aliased("https://example.com/2", "other");
        CreateLinkCommand repeat = aliased("https://example.com/3", "promo");
        CreateLinkCommand differentCase = aliased("https://example.com/4", "PROMO");
        when(linkService.create(any(), isNull())).thenAnswer(invocation ->
                created(invocation.<CreateLinkCommand>getArgument(0).customAlias()));

        BulkCreation creation = service(null).createAll(List.of(first, other, repeat, differentCase), null, CLIENT)
                .creation();

        assertThat(creation.items()).extracting(Item::succeeded).containsExactly(true, true, false, true);
        assertThat(creation.items().get(2).error().type()).isEqualTo(URI.create("urn:test:AliasConflictException"));
        assertThat(creation.items().get(2).error().detail())
                .isEqualTo("The alias 'promo' is already requested by item 0 of this batch.");
        verify(linkService, times(1)).create(first, null);
        verify(linkService, never()).create(repeat, null);
    }

    @Test
    void theFirstItemOfAnAliasIsAttemptedEvenIfItFails() {
        CreateLinkCommand first = aliased("http://10.0.0.1/", "promo");
        CreateLinkCommand repeat = aliased("https://example.com/", "promo");
        when(linkService.create(first, null)).thenThrow(new UrlRejectedException("private"));

        BulkCreation creation = service(null).createAll(List.of(first, repeat), null, CLIENT).creation();

        assertThat(creation.items()).extracting(item -> item.error().type().toString())
                .containsExactly("urn:test:UrlRejectedException", "urn:test:AliasConflictException");
        verify(linkService, never()).create(repeat, null);
    }

    @Test
    void chargesOneTokenPerItemAndReportsTheBucket() {
        RecordingRateLimiter limiter = new RecordingRateLimiter(5, 5);
        when(linkService.create(any(), isNull())).thenReturn(created("aaaa"));

        BulkCreationResult result = service(limiter).createAll(List.of(command("https://example.com/1"),
                command("https://example.com/2"), command("https://example.com/3")), null, CLIENT);

        assertThat(limiter.attempts()).containsExactly(CLIENT + ":3");
        assertThat(result.rateLimit()).isEqualTo(new BulkCreationResult.RateLimitStatus(5, 2));
    }

    @Test
    void aBatchTheBucketCannotCoverCreatesNothing() {
        RecordingRateLimiter limiter = new RecordingRateLimiter(5, 2);

        assertThatThrownBy(() -> service(limiter).createAll(List.of(command("https://example.com/1"),
                command("https://example.com/2"), command("https://example.com/3")), null, CLIENT))
                .isInstanceOfSatisfying(RateLimitExceededException.class, e -> {
                    assertThat(e.getRequestedTokens()).isEqualTo(3);
                    assertThat(e.getLimit()).isEqualTo(5);
                    assertThat(e.getRemaining()).isEqualTo(2);
                    assertThat(e.getRetryAfterSeconds()).isPositive();
                });
        verifyNoInteractions(linkService);
    }

    @Test
    void aBatchLargerThanTheBucketCapacityIsRejectedWithoutCharging() {
        RecordingRateLimiter limiter = new RecordingRateLimiter(2, 2);

        assertThatThrownBy(() -> service(limiter).createAll(List.of(command("https://example.com/1"),
                command("https://example.com/2"), command("https://example.com/3")), null, CLIENT))
                .isInstanceOfSatisfying(BatchTooLargeException.class, e -> {
                    assertThat(e.getItems()).isEqualTo(3);
                    assertThat(e.getMaxItems()).isEqualTo(2);
                });
        assertThat(limiter.attempts()).isEmpty();
        verifyNoInteractions(linkService, idempotency);
    }

    @Test
    void rejectsEmptyAndOversizedBatches() {
        BulkLinkService service = service(null);
        List<CreateLinkCommand> tooMany = Collections.nCopies(BulkLinkService.MAX_ITEMS + 1,
                command("https://example.com/"));

        assertThatThrownBy(() -> service.createAll(List.of(), null, CLIENT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createAll(tooMany, null, CLIENT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void withAKeyTheWholeOutcomeRunsOnceThroughTheIdempotencyService() {
        RecordingRateLimiter limiter = new RecordingRateLimiter(5, 5);
        List<CreateLinkCommand> commands = List.of(command("https://example.com/1"), command("http://10.0.0.1/"));
        when(linkService.create(commands.get(0), null)).thenReturn(created("aaaa"));
        when(linkService.create(commands.get(1), null)).thenThrow(new UrlRejectedException("private"));
        when(idempotency.fingerprint(anyString(), any())).thenReturn("f".repeat(64));
        // the item count selects the longer takeover window of a bulk reservation
        when(idempotency.execute(eq("key-1"), eq("f".repeat(64)), eq(200), eq(BulkCreation.class), eq(2), any()))
                .thenAnswer(invocation -> new IdempotentResult<>(
                        invocation.<Supplier<BulkCreation>>getArgument(5).get(), 200, false));

        BulkCreationResult result = service(limiter).createAll(commands, "key-1", CLIENT);

        verify(idempotency).fingerprint(BulkLinkService.IDEMPOTENCY_SCOPE, commands);
        assertThat(result.replayed()).isFalse();
        assertThat(result.creation().createdCount()).isEqualTo(1);
        assertThat(result.creation().failedCount()).isEqualTo(1);
        assertThat(limiter.attempts()).containsExactly(CLIENT + ":2");
    }

    @Test
    void aReplayIsReturnedWithoutChargingOrCreatingAnything() {
        RecordingRateLimiter limiter = new RecordingRateLimiter(5, 5);
        BulkCreation stored = new BulkCreation(List.of(Item.created(0, view("aaaa"))));
        when(idempotency.fingerprint(anyString(), any())).thenReturn("f".repeat(64));
        when(idempotency.<BulkCreation>execute(anyString(), anyString(), anyInt(), eq(BulkCreation.class), anyInt(),
                any()))
                .thenReturn(new IdempotentResult<>(stored, 200, true));

        BulkCreationResult result = service(limiter).createAll(List.of(command("https://example.com/1")), "key-1",
                CLIENT);

        assertThat(result.replayed()).isTrue();
        assertThat(result.creation()).isEqualTo(stored);
        assertThat(result.rateLimit()).isNull();
        assertThat(limiter.attempts()).isEmpty();
        verifyNoInteractions(linkService);
    }

    @Test
    void idempotencyFailuresPropagate() {
        when(idempotency.fingerprint(anyString(), any())).thenReturn("f".repeat(64));
        when(idempotency.<BulkCreation>execute(anyString(), anyString(), anyInt(), eq(BulkCreation.class), anyInt(),
                any()))
                .thenThrow(new IdempotencyConflictException());

        assertThatThrownBy(() -> service(null).createAll(List.of(command("https://example.com/1")), "key-1", CLIENT))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    private BulkLinkService service(RateLimiter limiter) {
        return new BulkLinkService(linkService, idempotency, errorMapper, Optional.ofNullable(limiter), meters);
    }

    private static CreateLinkCommand command(String url) {
        return new CreateLinkCommand(url, null, null, null);
    }

    private static CreateLinkCommand aliased(String url, String alias) {
        return new CreateLinkCommand(url, alias, null, null);
    }

    private static LinkView view(String code) {
        return new LinkView(code, "https://sho.rt/" + code, "https://example.com/", LinkStatus.ACTIVE, NOW, null, 0);
    }

    private static CreatedLink created(String code) {
        return new CreatedLink(view(code), false);
    }
}
