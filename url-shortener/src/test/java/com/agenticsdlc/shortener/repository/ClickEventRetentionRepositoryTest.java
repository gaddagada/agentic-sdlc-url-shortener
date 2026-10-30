package com.agenticsdlc.shortener.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import com.agenticsdlc.shortener.domain.ClickEvent;
import com.agenticsdlc.shortener.domain.Link;
import com.agenticsdlc.shortener.domain.UserAgentFamily;

@DataJpaTest
class ClickEventRetentionRepositoryTest {

    private static final Instant CUTOFF = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private LinkRepository links;

    @Autowired
    private ClickEventRepository clicks;

    @Test
    void deletesOnlyEventsStrictlyBeforeTheCutoff() {
        long linkId = links.save(Link.create("ret0001", "https://example.com", CUTOFF, null, false)).getId();
        clicks.save(new ClickEvent(linkId, CUTOFF.minusSeconds(1), null, UserAgentFamily.CHROME, "old"));
        clicks.save(new ClickEvent(linkId, CUTOFF, null, UserAgentFamily.CHROME, "boundary"));
        clicks.save(new ClickEvent(linkId, CUTOFF.plusSeconds(1), null, UserAgentFamily.FIREFOX, "new"));
        clicks.flush();

        int deleted = clicks.deleteOccurredBefore(CUTOFF);

        assertThat(deleted).isEqualTo(1);
        assertThat(clicks.findAll()).extracting(ClickEvent::getIpHash).containsExactlyInAnyOrder("boundary", "new");
    }

    @Test
    void findsTheOldestEventTime() {
        clicks.deleteAllInBatch(); // rolled back with the test transaction
        assertThat(clicks.findOldestOccurredAt()).isEmpty();

        long linkId = links.save(Link.create("ret0002", "https://example.com", CUTOFF, null, false)).getId();
        clicks.save(new ClickEvent(linkId, CUTOFF.plusSeconds(60), null, UserAgentFamily.CHROME, "later"));
        clicks.save(new ClickEvent(linkId, CUTOFF.minusSeconds(3_600), null, UserAgentFamily.CHROME, "oldest"));
        clicks.flush();

        assertThat(clicks.findOldestOccurredAt()).contains(CUTOFF.minusSeconds(3_600));
    }
}
