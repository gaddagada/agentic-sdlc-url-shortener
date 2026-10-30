package com.agenticsdlc.shortener.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import com.agenticsdlc.shortener.domain.Link;
import com.agenticsdlc.shortener.domain.LinkStatus;

/**
 * Runs against the Flyway-migrated schema on in-memory H2 with {@code ddl-auto=validate}, so it also proves that the
 * entity mappings match {@code V1__init.sql}.
 */
@DataJpaTest
class LinkRepositoryTest {

    private static final Instant CREATED = Instant.parse("2026-01-15T10:00:00.123Z");

    @Autowired
    private LinkRepository links;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void persistsAndFindsLinkByCode() {
        links.saveAndFlush(Link.create("abc1234", "https://example.com/a", CREATED, CREATED.plusSeconds(60), false));
        entityManager.clear();

        Link found = links.findByCode("abc1234").orElseThrow();

        assertThat(found.getId()).isNotNull();
        assertThat(found.getOriginalUrl()).isEqualTo("https://example.com/a");
        assertThat(found.getStoredStatus()).isEqualTo(LinkStatus.ACTIVE);
        assertThat(found.getCreatedAt()).isEqualTo(CREATED);
        assertThat(found.getExpiresAt()).isEqualTo(CREATED.plusSeconds(60));
        assertThat(found.getClickCount()).isZero();
        assertThat(found.isCustomAlias()).isFalse();
    }

    @Test
    void codesAreCaseSensitive() {
        links.saveAndFlush(Link.create("AbCdEfG", "https://example.com/a", CREATED, null, false));

        assertThat(links.existsByCode("AbCdEfG")).isTrue();
        assertThat(links.existsByCode("abcdefg")).isFalse();
        assertThat(links.findByCode("ABCDEFG")).isEmpty();
    }

    @Test
    void storesInstantsAsUtcWallClockTime() {
        links.saveAndFlush(Link.create("utc0001", "https://example.com/a", CREATED, null, false));

        Object stored = entityManager.getEntityManager()
                .createNativeQuery("select cast(created_at as varchar) from links where code = 'utc0001'")
                .getSingleResult();

        assertThat(stored.toString()).startsWith("2026-01-15 10:00:00.123");
    }

    @Test
    void rejectsDuplicateCodes() {
        links.saveAndFlush(Link.create("dup0001", "https://example.com/a", CREATED, null, false));

        assertThatThrownBy(() ->
                links.saveAndFlush(Link.create("dup0001", "https://example.com/b", CREATED, null, true)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void incrementsClickCountAtomically() {
        Link link = links.saveAndFlush(Link.create("cnt0001", "https://example.com/a", CREATED, null, false));

        assertThat(links.incrementClickCount(link.getId())).isEqualTo(1);
        assertThat(links.incrementClickCount(link.getId())).isEqualTo(1);
        entityManager.clear();

        assertThat(links.findByCode("cnt0001").orElseThrow().getClickCount()).isEqualTo(2);
    }

    @Test
    void disablingDoesNotOverwriteConcurrentClickIncrements() {
        Link link = links.saveAndFlush(Link.create("dyn0001", "https://example.com/a", CREATED, null, false));
        Link loaded = links.findById(link.getId()).orElseThrow();
        links.incrementClickCount(link.getId()); // happens after "loaded" was read, like a concurrent click

        loaded.disable();
        links.saveAndFlush(loaded);
        entityManager.clear();

        Link reloaded = links.findByCode("dyn0001").orElseThrow();
        assertThat(reloaded.getStoredStatus()).isEqualTo(LinkStatus.DISABLED);
        assertThat(reloaded.getClickCount()).isEqualTo(1);
    }
}
