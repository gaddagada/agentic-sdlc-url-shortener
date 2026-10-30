package com.agenticsdlc.shortener.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.agenticsdlc.shortener.config.ShortenerProperties;
import com.agenticsdlc.shortener.repository.ClickEventRepository;
import com.agenticsdlc.shortener.support.TestProperties;

class ClickRetentionJobTest {

    private static final Instant NOW = Instant.parse("2026-04-01T12:00:00Z");

    private final ClickEventRepository repository = mock(ClickEventRepository.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void purgesEventsOlderThanTheConfiguredRetentionOneDayAtATime() {
        Instant cutoff = NOW.minus(Duration.ofDays(30));
        Instant oldest = cutoff.minus(Duration.ofHours(60)); // 2.5 days of backlog
        Instant dayOneEnd = oldest.plus(Duration.ofDays(1));
        Instant dayTwoEnd = dayOneEnd.plus(Duration.ofDays(1));
        when(repository.findOldestOccurredAt())
                .thenReturn(Optional.of(oldest), Optional.of(dayOneEnd), Optional.of(dayTwoEnd));
        when(repository.deleteOccurredBefore(dayOneEnd)).thenReturn(4);
        when(repository.deleteOccurredBefore(dayTwoEnd)).thenReturn(2);
        when(repository.deleteOccurredBefore(cutoff)).thenReturn(1);

        ClickRetentionJob job = new ClickRetentionJob(repository, properties(Duration.ofDays(30)), clock);

        assertThat(job.purgeExpired()).isEqualTo(7);
        assertThat(job.retention()).isEqualTo(Duration.ofDays(30));
        InOrder order = inOrder(repository);
        order.verify(repository).deleteOccurredBefore(dayOneEnd);
        order.verify(repository).deleteOccurredBefore(dayTwoEnd);
        order.verify(repository).deleteOccurredBefore(cutoff);
        order.verifyNoMoreInteractions();
    }

    @Test
    void skipsDaysWithoutEvents() {
        Instant cutoff = NOW.minus(Duration.ofDays(90));
        Instant ancient = cutoff.minus(Duration.ofDays(400));
        Instant recent = cutoff.minus(Duration.ofHours(3));
        when(repository.findOldestOccurredAt()).thenReturn(Optional.of(ancient), Optional.of(recent));
        when(repository.deleteOccurredBefore(any())).thenReturn(1);

        ClickRetentionJob job = new ClickRetentionJob(repository, properties(Duration.ofDays(90)), clock);

        assertThat(job.purgeExpired()).isEqualTo(2);
        verify(repository).deleteOccurredBefore(ancient.plus(Duration.ofDays(1)));
        verify(repository).deleteOccurredBefore(cutoff);
    }

    @Test
    void doesNothingWhenNoEventIsBeyondRetention() {
        when(repository.findOldestOccurredAt()).thenReturn(Optional.of(NOW.minus(Duration.ofDays(89))));

        ClickRetentionJob job = new ClickRetentionJob(repository, properties(Duration.ofDays(90)), clock);

        assertThat(job.purgeExpired()).isZero();
        verify(repository, never()).deleteOccurredBefore(any());
        verify(repository, never()).deleteAll();
    }

    @Test
    void doesNothingWithoutEvents() {
        when(repository.findOldestOccurredAt()).thenReturn(Optional.empty());

        ClickRetentionJob job = new ClickRetentionJob(repository, properties(Duration.ofDays(90)), clock);

        assertThat(job.purgeExpired()).isZero();
        verify(repository, never()).deleteOccurredBefore(any());
    }

    private static ShortenerProperties properties(Duration retention) {
        return TestProperties.withAnalytics(TestProperties.analytics("salt", retention));
    }
}
