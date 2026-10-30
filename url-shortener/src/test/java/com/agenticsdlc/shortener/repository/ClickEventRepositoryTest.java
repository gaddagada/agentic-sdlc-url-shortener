package com.agenticsdlc.shortener.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;

import com.agenticsdlc.shortener.domain.ClickEvent;
import com.agenticsdlc.shortener.domain.Link;
import com.agenticsdlc.shortener.domain.UserAgentFamily;

@DataJpaTest
class ClickEventRepositoryTest {

    private static final Instant WINDOW_START = Instant.parse("2026-01-14T00:00:00Z");

    @Autowired
    private LinkRepository links;

    @Autowired
    private ClickEventRepository clicks;

    private long linkId;
    private long otherLinkId;

    @BeforeEach
    void setUp() {
        linkId = links.save(Link.create("evt0001", "https://example.com", WINDOW_START, null, false)).getId();
        otherLinkId = links.save(Link.create("evt0002", "https://example.com", WINDOW_START, null, false)).getId();

        click(linkId, "2026-01-13T23:59:59Z", "old.example.org", UserAgentFamily.CHROME, "v1"); // before window
        click(linkId, "2026-01-14T00:00:00Z", "news.example.org", UserAgentFamily.CHROME, "v1");
        click(linkId, "2026-01-14T23:59:59Z", "news.example.org", UserAgentFamily.FIREFOX, "v2");
        click(linkId, "2026-01-15T00:00:01Z", null, UserAgentFamily.CHROME, "v1");
        click(linkId, "2026-01-15T12:00:00Z", null, UserAgentFamily.BOT, "v3");
        click(linkId, "2026-01-15T13:00:00Z", "blog.example.com", UserAgentFamily.CHROME, "v3");
        click(otherLinkId, "2026-01-15T13:00:00Z", "news.example.org", UserAgentFamily.SAFARI, "v9");
        clicks.flush();
    }

    @Test
    void countsClicksInsideTheWindowOnly() {
        assertThat(clicks.countByLinkIdAndOccurredAtGreaterThanEqual(linkId, WINDOW_START)).isEqualTo(5);
    }

    @Test
    void countsDistinctVisitorHashes() {
        assertThat(clicks.countDistinctVisitors(linkId, WINDOW_START)).isEqualTo(3);
    }

    @Test
    void groupsClicksByUtcDay() {
        assertThat(clicks.countPerDay(linkId, WINDOW_START)).containsExactly(
                new DailyClickCount(LocalDate.of(2026, 1, 14), 2),
                new DailyClickCount(LocalDate.of(2026, 1, 15), 3));
    }

    @Test
    void ranksReferrersWithDirectTrafficAsNullHost() {
        assertThat(clicks.topReferrers(linkId, WINDOW_START, PageRequest.of(0, 10)))
                .containsExactlyInAnyOrder(
                        new ReferrerClickCount("news.example.org", 2),
                        new ReferrerClickCount(null, 2),
                        new ReferrerClickCount("blog.example.com", 1))
                .last().isEqualTo(new ReferrerClickCount("blog.example.com", 1));
    }

    @Test
    void limitsTopReferrers() {
        assertThat(clicks.topReferrers(linkId, WINDOW_START, PageRequest.of(0, 1))).hasSize(1)
                .first().extracting(ReferrerClickCount::clicks).isEqualTo(2L);
    }

    @Test
    void countsPerUserAgentFamilyOrderedByClicks() {
        assertThat(clicks.countPerUserAgentFamily(linkId, WINDOW_START)).containsExactly(
                new UserAgentClickCount(UserAgentFamily.CHROME, 3),
                new UserAgentClickCount(UserAgentFamily.BOT, 1),
                new UserAgentClickCount(UserAgentFamily.FIREFOX, 1));
    }

    private void click(long id, String at, String referrer, UserAgentFamily family, String visitor) {
        clicks.save(new ClickEvent(id, Instant.parse(at), referrer, family, visitor.repeat(32).substring(0, 64)));
    }
}
