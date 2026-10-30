package com.agenticsdlc.shortener.service;

import java.time.LocalDate;
import java.util.List;

/**
 * Aggregated click analytics of one link over a window of whole UTC days ending today.
 *
 * @param code              short code
 * @param windowDays        number of days in the window (1..90)
 * @param totalClicks       clicks in the window
 * @param uniqueVisitors    distinct visitor hashes in the window (per-day salted, see {@link IpAnonymizer})
 * @param clicksByDay       one entry per day of the window, oldest first, zero-filled
 * @param topReferrers      up to 10 referrer hosts by clicks; {@code "direct"} when no referrer was sent
 * @param byUserAgentFamily clicks per user-agent family
 */
public record LinkAnalytics(
        String code,
        int windowDays,
        long totalClicks,
        long uniqueVisitors,
        List<DailyClicks> clicksByDay,
        List<ReferrerClicks> topReferrers,
        List<UserAgentClicks> byUserAgentFamily) {

    /** Defensive copies keep the read model immutable. */
    public LinkAnalytics {
        clicksByDay = List.copyOf(clicksByDay);
        topReferrers = List.copyOf(topReferrers);
        byUserAgentFamily = List.copyOf(byUserAgentFamily);
    }

    /**
     * Clicks on one UTC day.
     *
     * @param date   the day
     * @param clicks clicks on that day
     */
    public record DailyClicks(LocalDate date, long clicks) {
    }

    /**
     * Clicks from one referrer host.
     *
     * @param referrer referrer host or {@code "direct"}
     * @param clicks   clicks from that referrer
     */
    public record ReferrerClicks(String referrer, long clicks) {
    }

    /**
     * Clicks from one user-agent family.
     *
     * @param family family name (see {@link com.agenticsdlc.shortener.domain.UserAgentFamily})
     * @param clicks clicks from that family
     */
    public record UserAgentClicks(String family, long clicks) {
    }
}
