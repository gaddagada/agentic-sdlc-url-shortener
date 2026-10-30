package com.agenticsdlc.shortener.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.agenticsdlc.shortener.domain.ClickEvent;
import com.agenticsdlc.shortener.repository.ClickEventRepository;
import com.agenticsdlc.shortener.repository.LinkRepository;

/**
 * Persists clicks asynchronously, off the redirect path.
 *
 * <p>Handles {@link ClickRecorded} events on the {@value #EXECUTOR} executor (virtual thread per task) and, in one
 * transaction, stores an anonymized {@link ClickEvent} and atomically increments the link's click counter.
 * Analytics are best effort: a failure is logged and never affects the redirect that already happened.
 */
@Service
public class ClickRecorder {

    /** Bean name of the executor running click persistence. */
    public static final String EXECUTOR = "clickRecorderExecutor";

    static final int MAX_REFERRER_HOST_LENGTH = 255;

    private static final Logger log = LoggerFactory.getLogger(ClickRecorder.class);

    private final ClickEventRepository clickEvents;
    private final LinkRepository links;
    private final UserAgentClassifier userAgentClassifier;
    private final IpAnonymizer ipAnonymizer;

    /**
     * Creates the recorder.
     *
     * @param clickEvents         click event repository
     * @param links               link repository (click counter)
     * @param userAgentClassifier user agent classification
     * @param ipAnonymizer        visitor hashing
     */
    public ClickRecorder(ClickEventRepository clickEvents, LinkRepository links,
                         UserAgentClassifier userAgentClassifier, IpAnonymizer ipAnonymizer) {
        this.clickEvents = clickEvents;
        this.links = links;
        this.userAgentClassifier = userAgentClassifier;
        this.ipAnonymizer = ipAnonymizer;
    }

    /**
     * Records one click.
     *
     * @param event the redirect that happened
     */
    @Async(EXECUTOR)
    @EventListener
    @Transactional
    public void onClick(ClickRecorded event) {
        LocalDate utcDay = LocalDate.ofInstant(event.occurredAt(), ZoneOffset.UTC);
        clickEvents.save(new ClickEvent(
                event.linkId(),
                event.occurredAt(),
                referrerHost(event.referrer()),
                userAgentClassifier.classify(event.userAgent()),
                ipAnonymizer.hash(event.clientIp(), utcDay)));
        links.incrementClickCount(event.linkId());
        log.debug("click recorded linkId={}", event.linkId());
    }

    /**
     * Extracts the lower-case host of a {@code Referer} header.
     *
     * @param referrer raw header value
     * @return the host, or {@code null} when absent or unparsable (counted as direct traffic)
     */
    static @Nullable String referrerHost(@Nullable String referrer) {
        if (referrer == null || referrer.isBlank()) {
            return null;
        }
        try {
            String host = new URI(referrer.strip()).getHost();
            if (host == null || host.isBlank() || host.length() > MAX_REFERRER_HOST_LENGTH) {
                return null;
            }
            return host.toLowerCase(Locale.ROOT);
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
