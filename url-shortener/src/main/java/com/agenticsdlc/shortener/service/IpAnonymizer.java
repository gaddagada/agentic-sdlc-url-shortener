package com.agenticsdlc.shortener.service;

import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.agenticsdlc.shortener.config.ShortenerProperties;

/**
 * Turns client IPs into non-reversible visitor hashes: {@code SHA-256(ip + ":" + dailySalt)} with
 * {@code dailySalt = shortener.analytics.ip-salt + ":" + UTC date}.
 *
 * <p>The secret salt prevents rainbow-table reversal of the small IPv4 space; the daily rotation prevents tracking
 * a visitor across days. Raw IPs are never persisted or logged.
 */
@Component
public class IpAnonymizer {

    static final String PLACEHOLDER_SALT = "change-me-in-production";

    private static final Logger log = LoggerFactory.getLogger(IpAnonymizer.class);

    private final String salt;

    /**
     * Creates the anonymizer.
     *
     * @param properties provides {@code shortener.analytics.ip-salt}
     */
    public IpAnonymizer(ShortenerProperties properties) {
        this.salt = properties.analytics().ipSalt();
        if (PLACEHOLDER_SALT.equals(salt)) {
            log.warn("shortener.analytics.ip-salt uses the placeholder value; set a secret salt in production");
        }
    }

    /**
     * Hashes a client IP for the given UTC day.
     *
     * @param clientIp client IP
     * @param utcDay   UTC day of the click
     * @return 64 character lower-case hex SHA-256
     */
    public String hash(String clientIp, LocalDate utcDay) {
        String dailySalt = salt + ":" + utcDay;
        return Sha256.hex(clientIp + ":" + dailySalt);
    }
}
