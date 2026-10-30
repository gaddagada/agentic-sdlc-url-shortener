package com.agenticsdlc.shortener.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import com.agenticsdlc.shortener.domain.Link;
import com.agenticsdlc.shortener.domain.UserAgentFamily;
import com.agenticsdlc.shortener.repository.ClickEventRepository;
import com.agenticsdlc.shortener.repository.DailyClickCount;
import com.agenticsdlc.shortener.repository.LinkRepository;
import com.agenticsdlc.shortener.repository.ReferrerClickCount;
import com.agenticsdlc.shortener.repository.UserAgentClickCount;
import com.agenticsdlc.shortener.service.LinkAnalytics.DailyClicks;
import com.agenticsdlc.shortener.service.LinkAnalytics.ReferrerClicks;
import com.agenticsdlc.shortener.service.LinkAnalytics.UserAgentClicks;
import com.agenticsdlc.shortener.service.exception.LinkNotFoundException;
import com.agenticsdlc.shortener.support.MutableClock;

class AnalyticsServiceTest {

    private static final long LINK_ID = 42L;
    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    private static final Instant WINDOW_START = Instant.parse("2026-01-13T00:00:00Z");

    private final LinkRepository links = mock(LinkRepository.class);
    private final ClickEventRepository clicks = mock(ClickEventRepository.class);
    private final AnalyticsService service = new AnalyticsService(links, clicks, new MutableClock(NOW));

    @BeforeEach
    void setUp() {
        Link link = Link.create("abc1234", "https://example.com", NOW.minusSeconds(86_400 * 5L), null, false);
        ReflectionTestUtils.setField(link, "id", LINK_ID);
        when(links.findByCode("abc1234")).thenReturn(Optional.of(link));
    }

    @Test
    void aggregatesOverWholeUtcDaysEndingToday() {
        when(clicks.countByLinkIdAndOccurredAtGreaterThanEqual(LINK_ID, WINDOW_START)).thenReturn(5L);
        when(clicks.countDistinctVisitors(LINK_ID, WINDOW_START)).thenReturn(3L);
        when(clicks.countPerDay(LINK_ID, WINDOW_START)).thenReturn(List.of(
                new DailyClickCount(LocalDate.of(2026, 1, 13), 2),
                new DailyClickCount(LocalDate.of(2026, 1, 15), 3)));
        when(clicks.topReferrers(eq(LINK_ID), eq(WINDOW_START), any())).thenReturn(List.of(
                new ReferrerClickCount("news.example.org", 3),
                new ReferrerClickCount(null, 2)));
        when(clicks.countPerUserAgentFamily(LINK_ID, WINDOW_START)).thenReturn(List.of(
                new UserAgentClickCount(UserAgentFamily.CHROME, 4),
                new UserAgentClickCount(UserAgentFamily.BOT, 1)));

        LinkAnalytics analytics = service.analyticsFor("abc1234", 3);

        assertThat(analytics.code()).isEqualTo("abc1234");
        assertThat(analytics.windowDays()).isEqualTo(3);
        assertThat(analytics.totalClicks()).isEqualTo(5);
        assertThat(analytics.uniqueVisitors()).isEqualTo(3);
        assertThat(analytics.clicksByDay()).containsExactly(
                new DailyClicks(LocalDate.of(2026, 1, 13), 2),
                new DailyClicks(LocalDate.of(2026, 1, 14), 0),
                new DailyClicks(LocalDate.of(2026, 1, 15), 3));
        assertThat(analytics.topReferrers()).containsExactly(
                new ReferrerClicks("news.example.org", 3),
                new ReferrerClicks(AnalyticsService.DIRECT_REFERRER, 2));
        assertThat(analytics.byUserAgentFamily()).containsExactly(
                new UserAgentClicks("CHROME", 4),
                new UserAgentClicks("BOT", 1));
        verify(clicks).topReferrers(LINK_ID, WINDOW_START, PageRequest.of(0, 10));
    }

    @Test
    void singleDayWindowStartsAtTodaysMidnight() {
        LinkAnalytics analytics = service.analyticsFor("abc1234", 1);

        assertThat(analytics.clicksByDay()).containsExactly(new DailyClicks(LocalDate.of(2026, 1, 15), 0));
        verify(clicks).countByLinkIdAndOccurredAtGreaterThanEqual(LINK_ID, Instant.parse("2026-01-15T00:00:00Z"));
    }

    @Test
    void ninetyDayWindowHasNinetyEntries() {
        assertThat(service.analyticsFor("abc1234", 90).clicksByDay()).hasSize(90)
                .first().extracting(DailyClicks::date).isEqualTo(LocalDate.of(2025, 10, 18));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 91})
    void rejectsWindowOutsideRange(int days) {
        assertThatThrownBy(() -> service.analyticsFor("abc1234", days)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownCodeIsNotFound() {
        when(links.findByCode("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.analyticsFor("missing", 7)).isInstanceOf(LinkNotFoundException.class);
    }
}
