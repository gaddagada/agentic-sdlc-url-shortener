package com.agenticsdlc.shortener.service;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.agenticsdlc.shortener.config.ShortenerProperties;

/**
 * Turns client IPs into non-reversible visitor hashes: {@code HMAC-SHA256(key = shortener.analytics.ip-salt,
 * message = UTC date + "|" + ip)}, hex encoded.
 *
 * <p>The secret key prevents brute-forcing the small IPv4 space (a plain salted SHA-256 with a leaked or guessable
 * salt can be reversed by hashing every address); including the UTC date in the message rotates the hash daily, so a
 * visitor cannot be tracked across days. Raw IPs are never persisted or logged.
 *
 * <p>Startup fails when the placeholder key {@value #PLACEHOLDER_SALT} is used under a production-like profile
 * ({@code postgres} or {@code prod}); local development only logs a warning.
 */
@Component
public class IpAnonymizer {

    static final String PLACEHOLDER_SALT = "change-me-in-production";
    static final String ALGORITHM = "HmacSHA256";
    /** Profiles under which the placeholder key is rejected at startup. */
    static final String[] PRODUCTION_PROFILES = {"postgres", "prod"};

    private static final Logger log = LoggerFactory.getLogger(IpAnonymizer.class);
    private static final HexFormat HEX = HexFormat.of();

    private final SecretKeySpec key;

    /**
     * Creates the anonymizer.
     *
     * @param properties  provides {@code shortener.analytics.ip-salt}, the HMAC key
     * @param environment active profiles, to reject the placeholder key in production
     * @throws IllegalStateException when the placeholder key is used under the {@code postgres} or {@code prod}
     *                               profile
     */
    public IpAnonymizer(ShortenerProperties properties, Environment environment) {
        String salt = properties.analytics().ipSalt();
        if (PLACEHOLDER_SALT.equals(salt)) {
            if (environment.matchesProfiles(PRODUCTION_PROFILES)) {
                throw new IllegalStateException("shortener.analytics.ip-salt uses the placeholder value '"
                        + PLACEHOLDER_SALT + "'; set a secret (e.g. SHORTENER_ANALYTICS_IP_SALT) for profiles "
                        + String.join(", ", PRODUCTION_PROFILES));
            }
            log.warn("shortener.analytics.ip-salt uses the placeholder value; set a secret salt in production");
        }
        this.key = new SecretKeySpec(salt.getBytes(StandardCharsets.UTF_8), ALGORITHM);
        newMac(); // fail at startup, not on the first click, if the key or algorithm is unusable
    }

    /**
     * Hashes a client IP for the given UTC day.
     *
     * @param clientIp client IP
     * @param utcDay   UTC day of the click
     * @return 64 character lower-case hex HMAC-SHA256
     */
    public String hash(String clientIp, LocalDate utcDay) {
        byte[] message = (utcDay + "|" + clientIp).getBytes(StandardCharsets.UTF_8);
        return HEX.formatHex(newMac().doFinal(message));
    }

    /** {@link Mac} is not thread-safe, so every hash uses its own instance (creation is cheap). */
    private Mac newMac() {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac;
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 is required by every Java platform", e);
        }
    }
}
