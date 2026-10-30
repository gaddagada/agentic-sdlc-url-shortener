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

import org.hibernate.annotations.DynamicUpdate;
import org.jspecify.annotations.Nullable;

/**
 * A short link: a unique code mapped to a target URL with an optional expiry.
 *
 * <p>{@code clickCount} is maintained with an atomic {@code UPDATE ... SET click_count = click_count + 1} by the click
 * recorder. {@link DynamicUpdate} makes Hibernate write only the columns that changed, so disabling a link never
 * overwrites a concurrently incremented counter with a stale value.
 */
@Entity
@Table(name = "links")
@DynamicUpdate
public class Link {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private @Nullable Long id;

    @Column(name = "code", nullable = false, unique = true, length = 32, updatable = false)
    private String code;

    @Column(name = "original_url", nullable = false, length = 2048, updatable = false)
    private String originalUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private LinkStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", updatable = false)
    private @Nullable Instant expiresAt;

    @Column(name = "click_count", nullable = false)
    private long clickCount;

    @Column(name = "custom_alias", nullable = false, updatable = false)
    private boolean customAlias;

    /** Required by JPA. */
    protected Link() {
        this.code = "";
        this.originalUrl = "";
        this.status = LinkStatus.ACTIVE;
        this.createdAt = Instant.EPOCH;
    }

    private Link(String code, String originalUrl, Instant createdAt, @Nullable Instant expiresAt, boolean customAlias) {
        this.code = Objects.requireNonNull(code, "code");
        this.originalUrl = Objects.requireNonNull(originalUrl, "originalUrl");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.expiresAt = expiresAt;
        this.customAlias = customAlias;
        this.status = LinkStatus.ACTIVE;
        this.clickCount = 0;
    }

    /**
     * Creates a new, active link.
     *
     * @param code        the unique short code (generated or custom alias)
     * @param originalUrl the validated target URL
     * @param createdAt   creation instant
     * @param expiresAt   optional expiry instant
     * @param customAlias whether {@code code} was chosen by the client
     * @return a transient link ready to be persisted
     */
    public static Link create(String code, String originalUrl, Instant createdAt, @Nullable Instant expiresAt,
                              boolean customAlias) {
        return new Link(code, originalUrl, createdAt, expiresAt, customAlias);
    }

    /** Soft-deletes the link. Idempotent. */
    public void disable() {
        this.status = LinkStatus.DISABLED;
    }

    /**
     * Returns the status observed at {@code now} (see {@link LinkStatus#effective}).
     *
     * @param now evaluation instant
     * @return effective status
     */
    public LinkStatus statusAt(Instant now) {
        return LinkStatus.effective(status, expiresAt, now);
    }

    public @Nullable Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getOriginalUrl() {
        return originalUrl;
    }

    /** {@return the persisted status, ACTIVE or DISABLED; see {@link #statusAt} for the effective one} */
    public LinkStatus getStoredStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public @Nullable Instant getExpiresAt() {
        return expiresAt;
    }

    public long getClickCount() {
        return clickCount;
    }

    public boolean isCustomAlias() {
        return customAlias;
    }
}
