package com.agenticsdlc.shortener.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.agenticsdlc.shortener.config.ShortenerProperties;
import com.agenticsdlc.shortener.repository.ClickEventRepository;

/**
 * Enforces the analytics data-retention policy: click events older than {@code shortener.analytics.retention}
 * (default 90 days) are purged hourly. Aggregated counters on the link itself are unaffected, so lifetime click
 * totals remain correct while per-event data (hashed visitor, referrer, user-agent family) is not kept forever.
 *
 * <p>The purge runs in batches of {@link #BATCH_WINDOW} (one day of events), starting at the oldest stored event and
 * moving up to the cutoff; each batch is a separate statement and transaction served by the {@code occurred_at}
 * index, so a large backlog (first run, or after downtime) never becomes one huge, long-locking delete. Gaps without
 * events are skipped because every batch starts at the oldest remaining event.
 */
@Service
public class ClickRetentionJob {

    /** Width of one delete batch. */
    static final Duration BATCH_WINDOW = Duration.ofDays(1);

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
     * Deletes click events older than the retention window, one {@link #BATCH_WINDOW} at a time.
     *
     * @return number of events purged
     */
    @Scheduled(initialDelay = 10, fixedDelay = 60, timeUnit = TimeUnit.MINUTES)
    public int purgeExpired() {
        Instant cutoff = clock.instant().minus(retention);
        int purged = 0;
        int batches = 0;
        Optional<Instant> oldest = clickEvents.findOldestOccurredAt();
        while (oldest.isPresent() && oldest.get().isBefore(cutoff)) {
            Instant windowEnd = oldest.get().plus(BATCH_WINDOW);
            if (windowEnd.isAfter(cutoff)) {
                windowEnd = cutoff;
            }
            purged += clickEvents.deleteOccurredBefore(windowEnd);
            batches++;
            if (windowEnd.equals(cutoff)) {
                break;
            }
            oldest = clickEvents.findOldestOccurredAt();
        }
        if (purged > 0) {
            log.info("purged click events beyond retention retention={} cutoff={} count={} batches={}", retention,
                    cutoff, purged, batches);
        }
        return purged;
    }

    /** @return the configured retention window */
    public Duration retention() {
        return retention;
    }
}
