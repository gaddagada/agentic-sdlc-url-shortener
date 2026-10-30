package com.agenticsdlc.shortener.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.agenticsdlc.shortener.domain.Link;

/**
 * Persistence for {@link Link}. Lookups by code are served by the unique index on {@code links.code}.
 */
public interface LinkRepository extends JpaRepository<Link, Long> {

    /**
     * Finds a link by its short code (case-sensitive).
     *
     * @param code short code or custom alias
     * @return the link, if present
     */
    Optional<Link> findByCode(String code);

    /**
     * Tells whether a code is already taken.
     *
     * @param code short code or custom alias
     * @return {@code true} when a link with this code exists
     */
    boolean existsByCode(String code);

    /**
     * Atomically increments the click counter in the database, without reading the entity first, so concurrent
     * clicks never lose updates.
     *
     * @param id link id
     * @return number of updated rows (0 when the link does not exist)
     */
    @Transactional
    @Modifying
    @Query("update Link l set l.clickCount = l.clickCount + 1 where l.id = :id")
    int incrementClickCount(@Param("id") long id);
}
