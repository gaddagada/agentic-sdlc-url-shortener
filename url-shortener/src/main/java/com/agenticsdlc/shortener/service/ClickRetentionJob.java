package com.agenticsdlc.shortener.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.agenticsdlc.shortener.config.ShortenerProperties;
import com.agenticsdlc.shortener.repository.ClickEventRepository;

/**
 * Enforces the analytics data-retention policy: click events older than {@code shortener.analytics.retention}
 * (default 90 days) are purged hourly. Aggregated counters on the link itself are unaffected, so lifetime click
 * totals remain correct while per-event data (hashed visitor, referrer, user-agent family) is not kept forever.
 */
@Service
public class ClickRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(ClickRetentionJob.class);

    private final ClickEventRepository clickEvents;
    private final Clock clock;
    private final Duration retention;

    /**
     * Creates the job.
     *
     * @param clickEvents click event repository
     * @param properties  provides {@code shortener.analytics.retention}
     * @param clock       time source
     */
    public ClickRetentionJob(ClickEventRepository clickEvents, ShortenerProperties properties, Clock clock) {
        this.clickEvents = clickEvents;
        this.clock = clock;
        this.retention = properties.analytics().retention();
    }

    /**
     * Deletes click events older than the retention window.
     *
     * @return number of events purged
     */
    @Scheduled(initialDelay = 10, fixedDelay = 60, timeUnit = TimeUnit.MINUTES)
    @Transactional
    public int purgeExpired() {
        Instant cutoff = clock.instant().minus(retention);
        int purged = clickEvents.deleteOccurredBefore(cutoff);
        if (purged > 0) {
            log.info("purged click events beyond retention retention={} cutoff={} count={}", retention, cutoff, purged);
        }
        return purged;
    }

    /** @return the configured retention window */
    public Duration retention() {
        return retention;
    }
}
