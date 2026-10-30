package com.agenticsdlc.shortener.service;

import java.util.Optional;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import com.agenticsdlc.shortener.repository.LinkRepository;

/**
 * Cached code-to-link lookup used by the redirect hot path ({@value #CACHE_NAME}: Caffeine, max 10 000 entries,
 * 60 s after write). A separate bean so that Spring's caching proxy applies to calls from {@link LinkService}.
 *
 * <p>Unknown codes are not cached (no negative caching), so a code created right after a miss resolves at once.
 */
@Component
public class LinkLookup {

    /** Name of the cache. */
    public static final String CACHE_NAME = "links-by-code";

    private final LinkRepository links;

    /**
     * Creates the lookup.
     *
     * @param links link repository
     */
    public LinkLookup(LinkRepository links) {
        this.links = links;
    }

    /**
     * Finds the snapshot of a link by code, from the cache when possible.
     *
     * @param code short code
     * @return the snapshot, empty when no such link exists
     */
    @Cacheable(cacheNames = CACHE_NAME, unless = "#result == null")
    public Optional<LinkSnapshot> findByCode(String code) {
        return links.findByCode(code).map(LinkSnapshot::of);
    }

    /**
     * Evicts a code from the cache. Within a transaction the eviction is deferred until after commit.
     *
     * @param code short code
     */
    @CacheEvict(cacheNames = CACHE_NAME)
    public void evict(String code) {
        // eviction is performed by the caching proxy
    }
}
