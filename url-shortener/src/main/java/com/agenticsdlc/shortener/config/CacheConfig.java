package com.agenticsdlc.shortener.config;

import java.time.Duration;
import java.util.List;

import com.github.benmanes.caffeine.cache.Caffeine;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.transaction.TransactionAwareCacheManagerProxy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.agenticsdlc.shortener.service.LinkLookup;

/**
 * Caffeine cache for the redirect hot path.
 *
 * <p>The manager is wrapped in a {@link TransactionAwareCacheManagerProxy}: evictions issued inside a transaction
 * (deactivating a link) are applied after commit, so a concurrent redirect cannot re-cache the pre-commit state.
 * Only the explicitly registered cache exists (no dynamic creation), which surfaces cache-name typos immediately.
 */
@Configuration(proxyBeanMethods = false)
@EnableCaching
class CacheConfig {

    static final long LINKS_MAX_ENTRIES = 10_000;
    static final Duration LINKS_TTL = Duration.ofSeconds(60);

    @Bean
    CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCacheNames(List.of());
        manager.setAllowNullValues(false);
        manager.registerCustomCache(LinkLookup.CACHE_NAME, Caffeine.newBuilder()
                .maximumSize(LINKS_MAX_ENTRIES)
                .expireAfterWrite(LINKS_TTL)
                .recordStats()
                .build());
        return new TransactionAwareCacheManagerProxy(manager);
    }
}
