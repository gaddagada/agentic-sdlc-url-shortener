package com.agenticsdlc.shortener.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import com.agenticsdlc.shortener.domain.Link;
import com.agenticsdlc.shortener.domain.LinkStatus;
import com.agenticsdlc.shortener.repository.LinkRepository;
import com.agenticsdlc.shortener.service.exception.AliasConflictException;
import com.agenticsdlc.shortener.service.exception.AliasRejectedException;
import com.agenticsdlc.shortener.service.exception.CodeGenerationException;
import com.agenticsdlc.shortener.service.exception.InvalidExpiryException;
import com.agenticsdlc.shortener.service.exception.LinkGoneException;
import com.agenticsdlc.shortener.service.exception.LinkNotFoundException;
import com.agenticsdlc.shortener.service.exception.UrlRejectedException;
import com.agenticsdlc.shortener.support.MutableClock;
import com.agenticsdlc.shortener.support.TestProperties;

class LinkServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    private static final String URL = "https://example.com/some/long/path";

    private final LinkRepository links = mock(LinkRepository.class);
    private final LinkLookup lookup = mock(LinkLookup.class);
    private final ShortCodeGenerator codes = mock(ShortCodeGenerator.class);
    private final IdempotencyService idempotency = mock(IdempotencyService.class);
    private final MutableClock clock = new MutableClock(NOW);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private LinkService service;

    @BeforeEach
    void setUp() {
        service = new LinkService(links, lookup, codes, new UrlValidator(TestProperties.defaults()), idempotency,
                TestProperties.defaults(), clock, meters);
        when(links.saveAndFlush(any(Link.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Nested
    class Create {

        @Test
        void createsActiveLinkWithGeneratedCode() {
            when(codes.generate()).thenReturn("abc1234");

            CreatedLink created = service.create(command(URL, null, null, null), null);

            assertThat(created.replayed()).isFalse();
            assertThat(created.link()).isEqualTo(new LinkView("abc1234", "https://sho.rt/abc1234", URL,
                    LinkStatus.ACTIVE, NOW, null, 0));
            assertThat(meters.counter("shortener.links.created", "kind", "generated").count()).isEqualTo(1);
            verifyNoInteractions(idempotency);
        }

        @Test
        void storesTheValidatedUrlWithoutSurroundingWhitespace() {
            when(codes.generate()).thenReturn("abc1234");

            service.create(command("  " + URL + " ", null, null, null), null);

            ArgumentCaptor<Link> saved = ArgumentCaptor.forClass(Link.class);
            verify(links).saveAndFlush(saved.capture());
            assertThat(saved.getValue().getOriginalUrl()).isEqualTo(URL);
            assertThat(saved.getValue().isCustomAlias()).isFalse();
        }

        @Test
        void rejectsUrlBeforeTouchingTheDatabase() {
            assertThatThrownBy(() -> service.create(command("http://10.0.0.1/", null, null, null), null))
                    .isInstanceOf(UrlRejectedException.class);
            verifyNoInteractions(links);
        }

        @Test
        void delegatesToIdempotencyServiceWhenKeyIsPresent() {
            LinkView stored = new LinkView("abc1234", "https://sho.rt/abc1234", URL, LinkStatus.ACTIVE, NOW, null, 0);
            when(idempotency.fingerprint(anyString(), any())).thenReturn("f".repeat(64));
            when(idempotency.<LinkView>execute(eq("key-1"), eq("f".repeat(64)), eq(201), eq(LinkView.class), any()))
                    .thenReturn(new IdempotentResult<>(stored, 201, true));

            CreatedLink created = service.create(command(URL, null, null, null), "key-1");

            assertThat(created.replayed()).isTrue();
            assertThat(created.link()).isEqualTo(stored);
            verify(idempotency).fingerprint("create-link", command(URL, null, null, null));
        }

        @Test
        @SuppressWarnings("unchecked")
        void idempotentExecutionCreatesTheLinkThroughTheSuppliedAction() {
            when(codes.generate()).thenReturn("abc1234");
            when(idempotency.fingerprint(anyString(), any())).thenReturn("f".repeat(64));
            when(idempotency.<LinkView>execute(anyString(), anyString(), anyInt(), eq(LinkView.class), any()))
                    .thenAnswer(invocation -> {
                        Supplier<LinkView> action = invocation.getArgument(4, Supplier.class);
                        return new IdempotentResult<>(action.get(), 201, false);
                    });

            CreatedLink created = service.create(command(URL, null, null, null), "key-1");

            assertThat(created.replayed()).isFalse();
            assertThat(created.link().code()).isEqualTo("abc1234");
        }
    }

    @Nested
    class CollisionRetry {

        @Test
        void retriesWithFreshCodeWhenCodeIsTaken() {
            when(codes.generate()).thenReturn("aaaaaaa", "bbbbbbb", "ccccccc");
            when(links.existsByCode("aaaaaaa")).thenReturn(true);
            when(links.existsByCode("bbbbbbb")).thenReturn(true);
            when(links.existsByCode("ccccccc")).thenReturn(false);

            CreatedLink created = service.create(command(URL, null, null, null), null);

            assertThat(created.link().code()).isEqualTo("ccccccc");
            verify(codes, times(3)).generate();
            verify(links, times(1)).saveAndFlush(any());
        }

        @Test
        void retriesWhenInsertHitsTheUniqueConstraint() {
            when(codes.generate()).thenReturn("aaaaaaa", "bbbbbbb");
            when(links.existsByCode("aaaaaaa")).thenReturn(false, true); // free when checked, taken on insert
            when(links.saveAndFlush(any(Link.class)))
                    .thenThrow(new DataIntegrityViolationException("uk_links_code"))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            CreatedLink created = service.create(command(URL, null, null, null), null);

            assertThat(created.link().code()).isEqualTo("bbbbbbb");
        }

        @Test
        void givesUpAfterThreeRetries() {
            when(codes.generate()).thenReturn("aaaaaaa");
            when(links.existsByCode("aaaaaaa")).thenReturn(true);

            assertThatThrownBy(() -> service.create(command(URL, null, null, null), null))
                    .isInstanceOf(CodeGenerationException.class);
            verify(codes, times(LinkService.MAX_CODE_ATTEMPTS)).generate();
            verify(links, never()).saveAndFlush(any());
        }

        @Test
        void rethrowsIntegrityViolationsThatAreNotCodeCollisions() {
            when(codes.generate()).thenReturn("aaaaaaa");
            DataIntegrityViolationException other = new DataIntegrityViolationException("some other constraint");
            when(links.saveAndFlush(any(Link.class))).thenThrow(other);

            assertThatThrownBy(() -> service.create(command(URL, null, null, null), null)).isSameAs(other);
        }
    }

    @Nested
    class CustomAlias {

        @Test
        void createsLinkWithAlias() {
            CreatedLink created = service.create(command(URL, "spring-sale", null, null), null);

            assertThat(created.link().code()).isEqualTo("spring-sale");
            assertThat(created.link().shortUrl()).isEqualTo("https://sho.rt/spring-sale");
            assertThat(meters.counter("shortener.links.created", "kind", "custom").count()).isEqualTo(1);
            verifyNoInteractions(codes);
        }

        @Test
        void rejectsAliasThatIsAlreadyTaken() {
            when(links.existsByCode("spring-sale")).thenReturn(true);

            assertThatThrownBy(() -> service.create(command(URL, "spring-sale", null, null), null))
                    .isInstanceOf(AliasConflictException.class)
                    .hasMessageContaining("spring-sale");
            verify(links, never()).saveAndFlush(any());
        }

        @Test
        void reportsConflictWhenAConcurrentRequestWinsTheInsert() {
            when(links.saveAndFlush(any(Link.class))).thenThrow(new DataIntegrityViolationException("uk_links_code"));

            assertThatThrownBy(() -> service.create(command(URL, "spring-sale", null, null), null))
                    .isInstanceOf(AliasConflictException.class);
        }

        @ParameterizedTest
        @ValueSource(strings = {"actuator", "swagger-ui", "health", "admin", "login", "static", "error",
            "Admin", "ERROR", "Swagger-UI"})
        void rejectsReservedAliasesCaseInsensitively(String alias) {
            assertThatThrownBy(() -> service.create(command(URL, alias, null, null), null))
                    .isInstanceOf(AliasRejectedException.class)
                    .hasMessageContaining("reserved");
            verifyNoInteractions(links);
        }

        @ParameterizedTest
        @ValueSource(strings = {"api", "API", "v3"})
        void reservedWordsShorterThanTheMinimumLengthAreRejectedToo(String alias) {
            // "api" and "v3" can never be aliases: they fail the 4-32 character rule before the reserved check.
            assertThatThrownBy(() -> service.create(command(URL, alias, null, null), null))
                    .isInstanceOf(AliasRejectedException.class);
            verifyNoInteractions(links);
        }

        @ParameterizedTest
        @ValueSource(strings = {"abc", "has space", "slash/es", "dot.dot", "ü-umlaut",
            "this-alias-is-way-too-long-to-be-ok"})
        void rejectsMalformedAliases(String alias) {
            assertThatThrownBy(() -> service.create(command(URL, alias, null, null), null))
                    .isInstanceOf(AliasRejectedException.class);
        }
    }

    @Nested
    class Expiry {

        @Test
        void computesExpiryFromTtl() {
            when(codes.generate()).thenReturn("abc1234");

            LinkView link = service.create(command(URL, null, 3_600L, null), null).link();

            assertThat(link.expiresAt()).isEqualTo(NOW.plusSeconds(3_600));
        }

        @Test
        void acceptsAbsoluteExpiryTruncatedToMilliseconds() {
            when(codes.generate()).thenReturn("abc1234");
            Instant requested = NOW.plus(Duration.ofDays(30)).plusNanos(123_456_789);

            LinkView link = service.create(command(URL, null, null, requested), null).link();

            assertThat(link.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(30)).plusMillis(123));
        }

        @Test
        void acceptsExpiryExactlyAtMaximumTtl() {
            when(codes.generate()).thenReturn("abc1234");

            LinkView link = service.create(command(URL, null, null, NOW.plus(Duration.ofDays(365))), null).link();

            assertThat(link.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(365)));
        }

        @Test
        void rejectsExpiryInThePastOrNow() {
            assertThatThrownBy(() -> service.create(command(URL, null, null, NOW.minusSeconds(1)), null))
                    .isInstanceOf(InvalidExpiryException.class).hasMessageContaining("future");
            assertThatThrownBy(() -> service.create(command(URL, null, null, NOW), null))
                    .isInstanceOf(InvalidExpiryException.class);
        }

        @Test
        void rejectsExpiryBeyondMaximumTtl() {
            Instant tooFar = NOW.plus(Duration.ofDays(365)).plusSeconds(1);

            assertThatThrownBy(() -> service.create(command(URL, null, null, tooFar), null))
                    .isInstanceOf(InvalidExpiryException.class).hasMessageContaining("365 days");
            assertThatThrownBy(() -> service.create(command(URL, null, Duration.ofDays(366).toSeconds(), null), null))
                    .isInstanceOf(InvalidExpiryException.class);
        }

        @Test
        void rejectsNonPositiveTtl() {
            assertThatThrownBy(() -> service.create(command(URL, null, 0L, null), null))
                    .isInstanceOf(InvalidExpiryException.class);
        }

        @Test
        void rejectsTtlAndExpiresAtTogether() {
            assertThatThrownBy(() -> service.create(command(URL, null, 60L, NOW.plusSeconds(60)), null))
                    .isInstanceOf(InvalidExpiryException.class).hasMessageContaining("mutually exclusive");
            verifyNoInteractions(links);
        }

        @Test
        void reportsDerivedExpiredStatus() {
            Link link = Link.create("abc1234", URL, NOW, NOW.plusSeconds(60), false);
            when(links.findByCode("abc1234")).thenReturn(Optional.of(link));

            clock.advance(Duration.ofSeconds(61));

            assertThat(service.get("abc1234").status()).isEqualTo(LinkStatus.EXPIRED);
        }
    }

    @Nested
    class Deactivate {

        @Test
        void disablesLinkAndEvictsCache() {
            Link link = Link.create("abc1234", URL, NOW, null, false);
            when(links.findByCode("abc1234")).thenReturn(Optional.of(link));

            service.deactivate("abc1234");

            assertThat(link.getStoredStatus()).isEqualTo(LinkStatus.DISABLED);
            verify(lookup).evict("abc1234");
        }

        @Test
        void isIdempotent() {
            Link link = Link.create("abc1234", URL, NOW, null, false);
            link.disable();
            when(links.findByCode("abc1234")).thenReturn(Optional.of(link));

            service.deactivate("abc1234");

            assertThat(link.statusAt(NOW)).isEqualTo(LinkStatus.DISABLED);
        }

        @Test
        void failsForUnknownCode() {
            when(links.findByCode("missing")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.deactivate("missing")).isInstanceOf(LinkNotFoundException.class);
            verify(lookup, never()).evict(anyString());
        }
    }

    @Nested
    class ResolveRedirect {

        @Test
        void resolvesActiveLink() {
            when(lookup.findByCode("abc1234")).thenReturn(Optional.of(
                    new LinkSnapshot(42L, "abc1234", URL, LinkStatus.ACTIVE, NOW.plusSeconds(60))));

            RedirectTarget target = service.resolveRedirect("abc1234");

            assertThat(target.linkId()).isEqualTo(42L);
            assertThat(target.location()).hasToString(URL);
        }

        @Test
        void expiredLinkIsGone() {
            when(lookup.findByCode("abc1234")).thenReturn(Optional.of(
                    new LinkSnapshot(42L, "abc1234", URL, LinkStatus.ACTIVE, NOW.minusSeconds(1))));

            assertThatThrownBy(() -> service.resolveRedirect("abc1234"))
                    .isInstanceOfSatisfying(LinkGoneException.class,
                            e -> assertThat(e.getStatus()).isEqualTo(LinkStatus.EXPIRED));
        }

        @Test
        void disabledLinkIsGone() {
            when(lookup.findByCode("abc1234")).thenReturn(Optional.of(
                    new LinkSnapshot(42L, "abc1234", URL, LinkStatus.DISABLED, null)));

            assertThatThrownBy(() -> service.resolveRedirect("abc1234"))
                    .isInstanceOfSatisfying(LinkGoneException.class,
                            e -> assertThat(e.getStatus()).isEqualTo(LinkStatus.DISABLED));
        }

        @Test
        void unknownCodeIsNotFound() {
            when(lookup.findByCode("missing")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.resolveRedirect("missing")).isInstanceOf(LinkNotFoundException.class);
        }
    }

    private static CreateLinkCommand command(String url, String alias, Long ttlSeconds, Instant expiresAt) {
        return new CreateLinkCommand(url, alias, ttlSeconds, expiresAt);
    }
}
