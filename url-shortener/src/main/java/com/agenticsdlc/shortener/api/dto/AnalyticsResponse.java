package com.agenticsdlc.shortener.api.dto;

import java.time.LocalDate;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import com.agenticsdlc.shortener.service.LinkAnalytics;

/**
 * Aggregated click analytics of a link.
 *
 * @param code              short code
 * @param windowDays        window size in days
 * @param totalClicks       clicks in the window
 * @param uniqueVisitors    distinct daily visitor hashes in the window
 * @param clicksByDay       one entry per UTC day, oldest first, zero-filled
 * @param topReferrers      up to 10 referrer hosts; {@code direct} when no referrer was sent
 * @param byUserAgentFamily clicks per user-agent family
 */
@Schema(name = "AnalyticsResponse", description = "Aggregated click analytics over whole UTC days ending today")
public record AnalyticsResponse(
        String code,
        int windowDays,
        long totalClicks,
        long uniqueVisitors,
        List<DailyClicks> clicksByDay,
        List<ReferrerClicks> topReferrers,
        List<UserAgentClicks> byUserAgentFamily) {

    /**
     * Maps the service read model.
     *
     * @param analytics link analytics
     * @return the response
     */
    public static AnalyticsResponse from(LinkAnalytics analytics) {
        return new AnalyticsResponse(
                analytics.code(),
                analytics.windowDays(),
                analytics.totalClicks(),
                analytics.uniqueVisitors(),
                analytics.clicksByDay().stream().map(d -> new DailyClicks(d.date(), d.clicks())).toList(),
                analytics.topReferrers().stream().map(r -> new ReferrerClicks(r.referrer(), r.clicks())).toList(),
                analytics.byUserAgentFamily().stream().map(u -> new UserAgentClicks(u.family(), u.clicks())).toList());
    }

    /**
     * Clicks on one UTC day.
     *
     * @param date   the day
     * @param clicks clicks on that day
     */
    @Schema(name = "DailyClicks")
    public record DailyClicks(LocalDate date, long clicks) {
    }

    /**
     * Clicks from one referrer host.
     *
     * @param referrer referrer host or {@code direct}
     * @param clicks   clicks from that referrer
     */
    @Schema(name = "ReferrerClicks")
    public record ReferrerClicks(@Schema(example = "news.ycombinator.com") String referrer, long clicks) {
    }

    /**
     * Clicks from one user-agent family.
     *
     * @param family user-agent family
     * @param clicks clicks from that family
     */
    @Schema(name = "UserAgentClicks")
    public record UserAgentClicks(
            @Schema(allowableValues = {"CHROME", "EDGE", "FIREFOX", "SAFARI", "OPERA", "IE", "BOT", "HTTP_CLIENT",
                    "OTHER", "UNKNOWN"}) String family,
            long clicks) {
    }
}
