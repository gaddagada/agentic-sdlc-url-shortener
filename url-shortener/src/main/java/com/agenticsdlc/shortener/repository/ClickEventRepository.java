package com.agenticsdlc.shortener.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.agenticsdlc.shortener.domain.ClickEvent;

/**
 * Persistence and aggregation queries for {@link ClickEvent}.
 *
 * <p>All aggregations run in the database and are bounded by {@code (link_id, occurred_at)}, which is covered by the
 * {@code idx_click_events_link_time} index; no click rows are loaded into memory.
 */
public interface ClickEventRepository extends JpaRepository<ClickEvent, Long> {

    /**
     * Deletes click events that occurred before the given instant (analytics data retention).
     *
     * @param before exclusive upper bound
     * @return number of rows deleted
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from ClickEvent c where c.occurredAt < :before")
    int deleteOccurredBefore(@Param("before") Instant before);

    /**
     * Counts clicks of a link since {@code from} (inclusive).
     *
     * @param linkId link id
     * @param from   window start
     * @return number of clicks
     */
    long countByLinkIdAndOccurredAtGreaterThanEqual(long linkId, Instant from);

    /**
     * Counts distinct visitor hashes of a link since {@code from}. Because the hash salt rotates daily, a visitor who
     * returns on another UTC day is counted again (privacy over precision).
     *
     * @param linkId link id
     * @param from   window start
     * @return number of distinct visitor hashes
     */
    @Query("select count(distinct c.ipHash) from ClickEvent c where c.linkId = :linkId and c.occurredAt >= :from")
    long countDistinctVisitors(@Param("linkId") long linkId, @Param("from") Instant from);

    /**
     * Clicks per UTC day since {@code from}. Days without clicks are absent.
     *
     * @param linkId link id
     * @param from   window start
     * @return counts ordered by day
     */
    @Query("""
            select new com.agenticsdlc.shortener.repository.DailyClickCount(cast(c.occurredAt as LocalDate), count(c))
            from ClickEvent c
            where c.linkId = :linkId and c.occurredAt >= :from
            group by cast(c.occurredAt as LocalDate)
            order by cast(c.occurredAt as LocalDate)
            """)
    List<DailyClickCount> countPerDay(@Param("linkId") long linkId, @Param("from") Instant from);

    /**
     * Most frequent referrer hosts since {@code from}; a {@code null} host represents direct traffic.
     *
     * @param linkId link id
     * @param from   window start
     * @param page   limits the number of rows (top N)
     * @return counts ordered by clicks descending
     */
    @Query("""
            select new com.agenticsdlc.shortener.repository.ReferrerClickCount(c.referrerHost, count(c))
            from ClickEvent c
            where c.linkId = :linkId and c.occurredAt >= :from
            group by c.referrerHost
            order by count(c) desc, c.referrerHost asc
            """)
    List<ReferrerClickCount> topReferrers(@Param("linkId") long linkId, @Param("from") Instant from, Pageable page);

    /**
     * Clicks per user-agent family since {@code from}.
     *
     * @param linkId link id
     * @param from   window start
     * @return counts ordered by clicks descending
     */
    @Query("""
            select new com.agenticsdlc.shortener.repository.UserAgentClickCount(c.userAgentFamily, count(c))
            from ClickEvent c
            where c.linkId = :linkId and c.occurredAt >= :from
            group by c.userAgentFamily
            order by count(c) desc, c.userAgentFamily asc
            """)
    List<UserAgentClickCount> countPerUserAgentFamily(@Param("linkId") long linkId, @Param("from") Instant from);
}
