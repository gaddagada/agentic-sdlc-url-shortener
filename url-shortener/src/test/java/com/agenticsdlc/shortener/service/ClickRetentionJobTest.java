package com.agenticsdlc.shortener.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.agenticsdlc.shortener.config.ShortenerProperties;
import com.agenticsdlc.shortener.repository.ClickEventRepository;

class ClickRetentionJobTest {

    private static final Instant NOW = Instant.parse("2026-04-01T12:00:00Z");

    private final ClickEventRepository repository = mock(ClickEventRepository.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void purgesEventsOlderThanTheConfiguredRetention() {
        ShortenerProperties properties = properties(Duration.ofDays(30));
        when(repository.deleteOccurredBefore(NOW.minus(Duration.ofDays(30)))).thenReturn(7);

        ClickRetentionJob job = new ClickRetentionJob(repository, properties, clock);

        assertThat(job.purgeExpired()).isEqualTo(7);
        assertThat(job.retention()).isEqualTo(Duration.ofDays(30));
        verify(repository).deleteOccurredBefore(NOW.minus(Duration.ofDays(30)));
    }

    @Test
    void defaultsToNinetyDays() {
        ShortenerProperties properties = properties(Duration.ofDays(90));
        when(repository.deleteOccurredBefore(any())).thenReturn(0);

        ClickRetentionJob job = new ClickRetentionJob(repository, properties, clock);

        assertThat(job.purgeExpired()).isZero();
        verify(repository).deleteOccurredBefore(NOW.minus(Duration.ofDays(90)));
        verify(repository, never()).deleteAll();
    }

    private static ShortenerProperties properties(Duration retention) {
        return new ShortenerProperties("http://localhost:8080", 7, Duration.ofDays(365), true, false,
                new ShortenerProperties.RateLimit(true, 20, 20), new ShortenerProperties.Analytics("salt", retention));
    }
}
