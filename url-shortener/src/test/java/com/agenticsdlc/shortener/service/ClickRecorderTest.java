package com.agenticsdlc.shortener.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import com.agenticsdlc.shortener.domain.ClickEvent;
import com.agenticsdlc.shortener.domain.UserAgentFamily;
import com.agenticsdlc.shortener.repository.ClickEventRepository;
import com.agenticsdlc.shortener.repository.LinkRepository;
import com.agenticsdlc.shortener.support.TestProperties;

class ClickRecorderTest {

    private static final String CHROME = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/130.0 Safari/537.36";

    private final ClickEventRepository clickEvents = mock(ClickEventRepository.class);
    private final LinkRepository links = mock(LinkRepository.class);
    private final IpAnonymizer anonymizer = new IpAnonymizer(TestProperties.defaults());
    private final ClickRecorder recorder = new ClickRecorder(clickEvents, links, new UserAgentClassifier(), anonymizer);

    @Test
    void storesAnonymizedClickAndIncrementsCounter() {
        Instant occurredAt = Instant.parse("2026-01-15T23:59:59Z");

        recorder.onClick(new ClickRecorded(42L, occurredAt, "https://News.Example.org/article?id=1", CHROME,
                "203.0.113.9"));

        ArgumentCaptor<ClickEvent> saved = ArgumentCaptor.forClass(ClickEvent.class);
        verify(clickEvents).save(saved.capture());
        ClickEvent event = saved.getValue();
        assertThat(event.getLinkId()).isEqualTo(42L);
        assertThat(event.getOccurredAt()).isEqualTo(occurredAt);
        assertThat(event.getReferrerHost()).isEqualTo("news.example.org");
        assertThat(event.getUserAgentFamily()).isEqualTo(UserAgentFamily.CHROME);
        assertThat(event.getIpHash())
                .isEqualTo(anonymizer.hash("203.0.113.9", LocalDate.of(2026, 1, 15)))
                .doesNotContain("203.0.113.9");
        verify(links).incrementClickCount(42L);
    }

    @Test
    void eventToStringRedactsTheClientIp() {
        ClickRecorded event = new ClickRecorded(1L, Instant.EPOCH, null, null, "203.0.113.9");

        assertThat(event.toString()).doesNotContain("203.0.113.9").contains("redacted");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "NULL", textBlock = """
        https://news.example.org/a?b=c          | news.example.org
        http://WWW.Example.COM                  | www.example.com
        android-app://com.google.android.gm/    | com.google.android.gm
        NULL                                    | NULL
        ''                                      | NULL
        not a url                               | NULL
        /relative/path                          | NULL
        """)
    void extractsReferrerHost(String referrer, String expectedHost) {
        assertThat(ClickRecorder.referrerHost(referrer)).isEqualTo(expectedHost);
    }
}
