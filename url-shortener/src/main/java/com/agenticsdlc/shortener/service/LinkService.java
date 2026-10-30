package com.agenticsdlc.shortener.service;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.agenticsdlc.shortener.config.ShortenerProperties;
import com.agenticsdlc.shortener.domain.Link;
import com.agenticsdlc.shortener.domain.LinkStatus;
import com.agenticsdlc.shortener.repository.LinkRepository;
import com.agenticsdlc.shortener.service.exception.AliasConflictException;
import com.agenticsdlc.shortener.service.exception.AliasRejectedException;
import com.agenticsdlc.shortener.service.exception.CodeGenerationException;
import com.agenticsdlc.shortener.service.exception.InvalidExpiryException;
import com.agenticsdlc.shortener.service.exception.LinkGoneException;
import com.agenticsdlc.shortener.service.exception.LinkNotFoundException;

/**
 * Link lifecycle: create (with optional custom alias, expiry and idempotency), read, deactivate, and resolution for
 * redirects.
 *
 * <p>Creation does not run in one surrounding transaction on purpose: each insert attempt is its own transaction,
 * so a unique-constraint collision on the code can be retried with a fresh code (a failed flush poisons the
 * persistence context of the transaction it happens in).
 */
@Service
public class LinkService {

    /** First attempt plus three retries on code collision. */
    static final int MAX_CODE_ATTEMPTS = 4;
    /** Custom alias syntax. Aliases are case-sensitive. */
    static final Pattern ALIAS_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{4,32}$");
    /** Aliases that would shadow application routes; compared case-insensitively. */
    static final Set<String> RESERVED_ALIASES = Set.of(
            "api", "actuator", "swagger-ui", "v3", "health", "admin", "login", "static", "error");

    private static final String IDEMPOTENCY_SCOPE = "create-link";
    private static final int HTTP_CREATED = 201;
    private static final Logger log = LoggerFactory.getLogger(LinkService.class);

    private final LinkRepository links;
    private final LinkLookup lookup;
    private final ShortCodeGenerator codeGenerator;
    private final UrlValidator urlValidator;
    private final IdempotencyService idempotency;
    private final Clock clock;
    private final String baseUrl;
    private final Duration maxTtl;
    private final Counter generatedLinksCreated;
    private final Counter customLinksCreated;

    /**
     * Creates the service.
     *
     * @param links         link repository
     * @param lookup        cached lookup used for redirects
     * @param codeGenerator random code generator
     * @param urlValidator  target URL policy
     * @param idempotency   idempotency support for creation
     * @param properties    base URL and maximum TTL
     * @param clock         time source
     * @param meterRegistry registry for {@code shortener.links.created}
     */
    public LinkService(LinkRepository links, LinkLookup lookup, ShortCodeGenerator codeGenerator,
                       UrlValidator urlValidator, IdempotencyService idempotency, ShortenerProperties properties,
                       Clock clock, MeterRegistry meterRegistry) {
        this.links = links;
        this.lookup = lookup;
        this.codeGenerator = codeGenerator;
        this.urlValidator = urlValidator;
        this.idempotency = idempotency;
        this.clock = clock;
        this.baseUrl = properties.baseUrl();
        this.maxTtl = properties.maxTtl();
        this.generatedLinksCreated = createdCounter(meterRegistry, "generated");
        this.customLinksCreated = createdCounter(meterRegistry, "custom");
    }

    /**
     * Creates a short link.
     *
     * @param command        what to create
     * @param idempotencyKey optional key; a repeated key with the same payload replays the first response
     * @return the created (or replayed) link
     * @throws com.agenticsdlc.shortener.service.exception.UrlRejectedException when the URL violates the policy
     * @throws AliasRejectedException  when the alias is malformed or reserved
     * @throws AliasConflictException  when the alias is taken
     * @throws InvalidExpiryException  when the expiry is ambiguous, past or too far ahead
     * @throws CodeGenerationException when no free code was found
     */
    public CreatedLink create(CreateLinkCommand command, @Nullable String idempotencyKey) {
        if (idempotencyKey == null) {
            return new CreatedLink(createLink(command), false);
        }
        String fingerprint = idempotency.fingerprint(IDEMPOTENCY_SCOPE, command);
        IdempotentResult<LinkView> result = idempotency.execute(idempotencyKey, fingerprint, HTTP_CREATED,
                LinkView.class, () -> createLink(command));
        return new CreatedLink(result.body(), result.replayed());
    }

    /**
     * Returns link metadata, including the current click count.
     *
     * @param code short code
     * @return the link view
     * @throws LinkNotFoundException when no such link exists
     */
    @Transactional(readOnly = true)
    public LinkView get(String code) {
        return toView(findExisting(code), now());
    }

    /**
     * Deactivates (soft-deletes) a link and evicts it from the redirect cache after commit. Idempotent.
     *
     * @param code short code
     * @throws LinkNotFoundException when no such link exists
     */
    @Transactional
    public void deactivate(String code) {
        Link link = findExisting(code);
        link.disable();
        lookup.evict(code);
        log.info("link deactivated code={}", code);
    }

    /**
     * Resolves a code for redirecting, using the cache.
     *
     * @param code short code
     * @return the redirect target
     * @throws LinkNotFoundException when no such link exists
     * @throws LinkGoneException     when the link is expired or disabled
     */
    public RedirectTarget resolveRedirect(String code) {
        LinkSnapshot link = lookup.findByCode(code).orElseThrow(() -> new LinkNotFoundException(code));
        LinkStatus status = link.statusAt(clock.instant());
        if (status != LinkStatus.ACTIVE) {
            throw new LinkGoneException(code, status);
        }
        return new RedirectTarget(link.id(), URI.create(link.originalUrl()));
    }

    private LinkView createLink(CreateLinkCommand command) {
        URI target = urlValidator.validate(command.url());
        Instant now = now();
        Instant expiresAt = resolveExpiry(command, now);
        String alias = command.customAlias();
        Link link = alias == null
                ? persistWithGeneratedCode(target.toString(), now, expiresAt)
                : persistWithAlias(alias, target.toString(), now, expiresAt);
        (alias == null ? generatedLinksCreated : customLinksCreated).increment();
        log.info("link created code={} customAlias={} expiresAt={}", link.getCode(), alias != null, expiresAt);
        return toView(link, now);
    }

    private @Nullable Instant resolveExpiry(CreateLinkCommand command, Instant now) {
        Long ttlSeconds = command.ttlSeconds();
        Instant requested = command.expiresAt();
        if (ttlSeconds != null && requested != null) {
            throw new InvalidExpiryException("ttlSeconds and expiresAt are mutually exclusive.");
        }
        if (ttlSeconds != null) {
            if (ttlSeconds <= 0 || ttlSeconds > maxTtl.toSeconds()) {
                throw new InvalidExpiryException("ttlSeconds must be between 1 and " + maxTtl.toSeconds() + ".");
            }
            return now.plusSeconds(ttlSeconds);
        }
        if (requested == null) {
            return null;
        }
        Instant expiresAt = requested.truncatedTo(ChronoUnit.MILLIS);
        if (!expiresAt.isAfter(now)) {
            throw new InvalidExpiryException("expiresAt must be in the future.");
        }
        if (expiresAt.isAfter(now.plus(maxTtl))) {
            throw new InvalidExpiryException("expiresAt must be at most " + maxTtl.toDays() + " days ahead.");
        }
        return expiresAt;
    }

    private Link persistWithAlias(String alias, String url, Instant now, @Nullable Instant expiresAt) {
        if (!ALIAS_PATTERN.matcher(alias).matches()) {
            throw new AliasRejectedException(alias, "it must be 4-32 characters of letters, digits, '_' or '-'");
        }
        if (RESERVED_ALIASES.contains(alias.toLowerCase(Locale.ROOT))) {
            throw new AliasRejectedException(alias, "it is reserved");
        }
        if (links.existsByCode(alias)) {
            throw new AliasConflictException(alias);
        }
        try {
            return links.saveAndFlush(Link.create(alias, url, now, expiresAt, true));
        } catch (DataIntegrityViolationException concurrentCreate) {
            throw new AliasConflictException(alias);
        }
    }

    private Link persistWithGeneratedCode(String url, Instant now, @Nullable Instant expiresAt) {
        for (int attempt = 1; attempt <= MAX_CODE_ATTEMPTS; attempt++) {
            String code = codeGenerator.generate();
            if (links.existsByCode(code)) {
                log.warn("short code collision attempt={}", attempt);
                continue;
            }
            try {
                return links.saveAndFlush(Link.create(code, url, now, expiresAt, false));
            } catch (DataIntegrityViolationException e) {
                if (!links.existsByCode(code)) {
                    throw e; // not a code collision
                }
                log.warn("short code collision on insert attempt={}", attempt);
            }
        }
        throw new CodeGenerationException(MAX_CODE_ATTEMPTS);
    }

    private Link findExisting(String code) {
        return links.findByCode(code).orElseThrow(() -> new LinkNotFoundException(code));
    }

    private LinkView toView(Link link, Instant now) {
        return new LinkView(link.getCode(), baseUrl + "/" + link.getCode(), link.getOriginalUrl(), link.statusAt(now),
                link.getCreatedAt(), link.getExpiresAt(), link.getClickCount());
    }

    /** Millisecond precision keeps API timestamps identical before and after a database round trip. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static Counter createdCounter(MeterRegistry registry, String kind) {
        return Counter.builder("shortener.links.created")
                .description("Short links created")
                .tag("kind", kind)
                .register(registry);
    }
}
