package com.agenticsdlc.shortener.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.agenticsdlc.shortener.domain.Link;
import com.agenticsdlc.shortener.repository.ClickEventRepository;
import com.agenticsdlc.shortener.repository.DailyClickCount;
import com.agenticsdlc.shortener.repository.LinkRepository;
import com.agenticsdlc.shortener.service.LinkAnalytics.DailyClicks;
import com.agenticsdlc.shortener.service.LinkAnalytics.ReferrerClicks;
import com.agenticsdlc.shortener.service.LinkAnalytics.UserAgentClicks;
import com.agenticsdlc.shortener.service.exception.LinkNotFoundException;

/**
 * Aggregates click analytics over a window of whole UTC days ending today (inclusive). Analytics remain available
 * for disabled and expired links.
 */
@Service
public class AnalyticsService {

    /** Smallest window in days. */
    public static final int MIN_WINDOW_DAYS = 1;
    /** Largest window in days. */
    public static final int MAX_WINDOW_DAYS = 90;
    /** Label used for clicks without a referrer. */
    public static final String DIRECT_REFERRER = "direct";

    static final int TOP_REFERRERS = 10;

    private final LinkRepository links;
    private final ClickEventRepository clicks;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param links  link repository
     * @param clicks click event repository
     * @param clock  time source
     */
    public AnalyticsService(LinkRepository links, ClickEventRepository clicks, Clock clock) {
        this.links = links;
        this.clicks = clicks;
        this.clock = clock;
    }

    /**
     * Computes analytics for a link.
     *
     * @param code short code
     * @param days window size in days, {@value #MIN_WINDOW_DAYS}..{@value #MAX_WINDOW_DAYS}
     * @return the aggregated analytics
     * @throws LinkNotFoundException    when no such link exists
     * @throws IllegalArgumentException when {@code days} is out of range (the API validates it first)
     */
    @Transactional(readOnly = true)
    public LinkAnalytics analyticsFor(String code, int days) {
        if (days < MIN_WINDOW_DAYS || days > MAX_WINDOW_DAYS) {
            throw new IllegalArgumentException("days must be between 1 and 90");
        }
        Link link = links.findByCode(code).orElseThrow(() -> new LinkNotFoundException(code));
        long linkId = Objects.requireNonNull(link.getId());
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        LocalDate firstDay = today.minusDays(days - 1L);
        Instant from = firstDay.atStartOfDay(ZoneOffset.UTC).toInstant();

        return new LinkAnalytics(
                code,
                days,
                clicks.countByLinkIdAndOccurredAtGreaterThanEqual(linkId, from),
                clicks.countDistinctVisitors(linkId, from),
                clicksByDay(linkId, from, firstDay, today),
                topReferrers(linkId, from),
                byUserAgentFamily(linkId, from));
    }

    private List<DailyClicks> clicksByDay(long linkId, Instant from, LocalDate firstDay, LocalDate today) {
        Map<LocalDate, Long> perDay = clicks.countPerDay(linkId, from).stream()
                .collect(Collectors.toMap(DailyClickCount::day, DailyClickCount::clicks));
        return firstDay.datesUntil(today.plusDays(1))
                .map(day -> new DailyClicks(day, perDay.getOrDefault(day, 0L)))
                .toList();
    }

    private List<ReferrerClicks> topReferrers(long linkId, Instant from) {
        return clicks.topReferrers(linkId, from, PageRequest.of(0, TOP_REFERRERS)).stream()
                .map(row -> new ReferrerClicks(row.referrerHost() == null ? DIRECT_REFERRER : row.referrerHost(),
                        row.clicks()))
                .toList();
    }

    private List<UserAgentClicks> byUserAgentFamily(long linkId, Instant from) {
        return clicks.countPerUserAgentFamily(linkId, from).stream()
                .map(row -> new UserAgentClicks(row.family().name(), row.clicks()))
                .toList();
    }
}
