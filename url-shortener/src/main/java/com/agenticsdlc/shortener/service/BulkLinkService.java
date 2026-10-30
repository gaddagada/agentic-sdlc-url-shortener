package com.agenticsdlc.shortener.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.agenticsdlc.shortener.service.BulkCreation.Item;
import com.agenticsdlc.shortener.service.BulkCreationResult.RateLimitStatus;
import com.agenticsdlc.shortener.service.exception.AliasConflictException;
import com.agenticsdlc.shortener.service.exception.BatchTooLargeException;
import com.agenticsdlc.shortener.service.exception.RateLimitExceededException;

/**
 * Creates up to {@value #MAX_ITEMS} links in one request with partial-failure semantics.
 *
 * <ul>
 *   <li><b>Per-item transactions.</b> Every item goes through {@link LinkService#create} exactly like a single
 *       request, whose inserts run in their own transactions. There is deliberately no surrounding transaction: a
 *       failing item (rejected URL, taken alias, ...) never rolls back the others; it is reported with the same
 *       problem type and status as the single-link endpoint (see {@link ItemErrorMapper}).</li>
 *   <li><b>Duplicate aliases</b> inside the batch are detected up front, before anything is created: the first item
 *       requesting an alias is processed, every later one fails with {@code alias-conflict} without being
 *       attempted.</li>
 *   <li><b>Rate limiting</b> charges one token per item, all-or-nothing, when the batch is executed: a request the
 *       client's bucket cannot cover fails with {@link RateLimitExceededException} and creates nothing; a batch larger
 *       than the bucket capacity could never be admitted and fails with {@link BatchTooLargeException}. Requests
 *       rejected before execution (validation errors, idempotency conflicts) and idempotent replays are not
 *       charged.</li>
 *   <li><b>Idempotency.</b> With an {@code Idempotency-Key}, the whole outcome (including per-item failures) is stored
 *       and replayed for a retry with the same items within 24 h; see {@link IdempotencyService}. A request rejected
 *       as a whole (e.g. rate limited) releases the key so that it can be retried.</li>
 * </ul>
 *
 * <p>Items are processed sequentially in request order, which keeps alias resolution and results deterministic.
 */
@Service
public class BulkLinkService {

    /** Largest number of items in one bulk request. */
    public static final int MAX_ITEMS = 100;

    static final String IDEMPOTENCY_SCOPE = "create-links-bulk";
    static final String ITEMS_METRIC = "shortener.links.bulk.items";

    private static final int HTTP_OK = 200;
    private static final Logger log = LoggerFactory.getLogger(BulkLinkService.class);

    private final LinkService linkService;
    private final IdempotencyService idempotency;
    private final ItemErrorMapper errorMapper;
    private final @Nullable RateLimiter rateLimiter;
    private final Counter createdItems;
    private final Counter failedItems;

    /**
     * Creates the service.
     *
     * @param linkService   single-link creation flow
     * @param idempotency   idempotency support
     * @param errorMapper   translates item failures (implemented by the API layer)
     * @param rateLimiter   link-creation rate limiter; absent when {@code shortener.rate-limit.enabled=false}
     * @param meterRegistry registry for {@value #ITEMS_METRIC}
     */
    public BulkLinkService(LinkService linkService, IdempotencyService idempotency, ItemErrorMapper errorMapper,
                           Optional<RateLimiter> rateLimiter, MeterRegistry meterRegistry) {
        this.linkService = linkService;
        this.idempotency = idempotency;
        this.errorMapper = errorMapper;
        this.rateLimiter = rateLimiter.orElse(null);
        this.createdItems = itemCounter(meterRegistry, "created");
        this.failedItems = itemCounter(meterRegistry, "failed");
    }

    /**
     * Creates the links of a batch.
     *
     * @param commands       the links to create, 1..{@value #MAX_ITEMS}
     * @param idempotencyKey optional key; a retry with the same items replays the stored outcome
     * @param client         rate-limit key of the caller (client IP)
     * @return per-item outcomes, whether they were replayed, and the rate-limit state after the charge
     * @throws IllegalArgumentException       when the batch is empty or has more than {@value #MAX_ITEMS} items
     *                                        (the API validates this first)
     * @throws BatchTooLargeException         when the batch exceeds the rate-limit capacity
     * @throws RateLimitExceededException     when the client's bucket cannot cover the batch now
     * @throws com.agenticsdlc.shortener.service.exception.IdempotencyConflictException   key reused with other items
     * @throws com.agenticsdlc.shortener.service.exception.IdempotencyInProgressException key still in flight
     */
    public BulkCreationResult createAll(List<CreateLinkCommand> commands, @Nullable String idempotencyKey,
                                        String client) {
        if (commands.isEmpty() || commands.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("a batch needs 1 to " + MAX_ITEMS + " items, got " + commands.size());
        }
        if (rateLimiter != null && commands.size() > rateLimiter.capacity()) {
            throw new BatchTooLargeException(commands.size(), rateLimiter.capacity());
        }
        AtomicReference<@Nullable RateLimitStatus> charged = new AtomicReference<>();
        Supplier<BulkCreation> execution = () -> {
            charged.set(charge(client, commands.size()));
            return createEach(commands);
        };
        if (idempotencyKey == null) {
            BulkCreation creation = execution.get();
            return new BulkCreationResult(creation, false, charged.get());
        }
        String fingerprint = idempotency.fingerprint(IDEMPOTENCY_SCOPE, commands);
        // a bulk request runs longer: its reservation is protected for longer before a retry may take it over
        IdempotentResult<BulkCreation> result = idempotency.execute(idempotencyKey, fingerprint, HTTP_OK,
                BulkCreation.class, commands.size(), execution);
        return new BulkCreationResult(result.body(), result.replayed(), charged.get());
    }

    private @Nullable RateLimitStatus charge(String client, int tokens) {
        if (rateLimiter == null) {
            return null;
        }
        RateLimiter.Decision decision = rateLimiter.tryConsume(client, tokens);
        if (!decision.allowed()) {
            log.debug("bulk request rate limited items={} retryAfterSeconds={}", tokens, decision.retryAfterSeconds());
            throw new RateLimitExceededException(tokens, rateLimiter.capacity(), decision.remaining(),
                    decision.retryAfterSeconds());
        }
        return new RateLimitStatus(rateLimiter.capacity(), decision.remaining());
    }

    private BulkCreation createEach(List<CreateLinkCommand> commands) {
        Map<Integer, Integer> earlierItemWithAlias = duplicateAliases(commands);
        List<Item> items = new ArrayList<>(commands.size());
        for (int index = 0; index < commands.size(); index++) {
            CreateLinkCommand command = commands.get(index);
            Integer firstIndex = earlierItemWithAlias.get(index);
            if (firstIndex != null) {
                String alias = Objects.requireNonNull(command.customAlias());
                items.add(failed(index, AliasConflictException.duplicateInBatch(alias, firstIndex)));
                continue;
            }
            try {
                items.add(Item.created(index, linkService.create(command, null).link()));
                createdItems.increment();
            } catch (RuntimeException failure) {
                items.add(failed(index, failure));
            }
        }
        BulkCreation creation = new BulkCreation(items);
        log.info("bulk link creation items={} created={} failed={}", items.size(), creation.createdCount(),
                creation.failedCount());
        return creation;
    }

    /** Maps the index of every item repeating an alias of an earlier item to the index of that first item. */
    private static Map<Integer, Integer> duplicateAliases(List<CreateLinkCommand> commands) {
        Map<String, Integer> firstIndexByAlias = new HashMap<>();
        Map<Integer, Integer> duplicates = new HashMap<>();
        for (int index = 0; index < commands.size(); index++) {
            String alias = commands.get(index).customAlias();
            if (alias != null) {
                Integer first = firstIndexByAlias.putIfAbsent(alias, index);
                if (first != null) {
                    duplicates.put(index, first);
                }
            }
        }
        return duplicates;
    }

    private Item failed(int index, RuntimeException failure) {
        failedItems.increment();
        return Item.failed(index, errorMapper.toItemError(failure));
    }

    private static Counter itemCounter(MeterRegistry registry, String outcome) {
        return Counter.builder(ITEMS_METRIC)
                .description("Items of bulk link creation requests by outcome")
                .tag("outcome", outcome)
                .register(registry);
    }
}
