package com.agenticsdlc.shortener.domain;

import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;
import org.jspecify.annotations.Nullable;

/**
 * One recorded redirect. Insert-only.
 *
 * <p>Privacy by design: the client IP is stored only as a salted SHA-256 hash ({@code ipHash}), the referrer only as
 * its host, and the user agent only as a coarse family.
 */
@Entity
@Table(name = "click_events")
@Immutable
public class ClickEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private @Nullable Long id;

    @Column(name = "link_id", nullable = false)
    private long linkId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "referrer_host", length = 255)
    private @Nullable String referrerHost;

    @Enumerated(EnumType.STRING)
    @Column(name = "user_agent_family", nullable = false, length = 32)
    private UserAgentFamily userAgentFamily;

    @Column(name = "ip_hash", nullable = false, length = 64)
    private String ipHash;

    /** Required by JPA. */
    protected ClickEvent() {
        this.occurredAt = Instant.EPOCH;
        this.userAgentFamily = UserAgentFamily.UNKNOWN;
        this.ipHash = "";
    }

    /**
     * Creates a click event.
     *
     * @param linkId          id of the clicked link
     * @param occurredAt      when the redirect happened
     * @param referrerHost    host of the {@code Referer} header, or {@code null} for direct traffic
     * @param userAgentFamily classified client family
     * @param ipHash          salted SHA-256 hex of the client IP
     */
    public ClickEvent(long linkId, Instant occurredAt, @Nullable String referrerHost, UserAgentFamily userAgentFamily,
                      String ipHash) {
        this.linkId = linkId;
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        this.referrerHost = referrerHost;
        this.userAgentFamily = Objects.requireNonNull(userAgentFamily, "userAgentFamily");
        this.ipHash = Objects.requireNonNull(ipHash, "ipHash");
    }

    public @Nullable Long getId() {
        return id;
    }

    public long getLinkId() {
        return linkId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public @Nullable String getReferrerHost() {
        return referrerHost;
    }

    public UserAgentFamily getUserAgentFamily() {
        return userAgentFamily;
    }

    public String getIpHash() {
        return ipHash;
    }
}
