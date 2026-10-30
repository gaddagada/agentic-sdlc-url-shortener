package com.agenticsdlc.shortener.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 helper producing lower-case hex digests.
 */
final class Sha256 {

    private static final HexFormat HEX = HexFormat.of();

    private Sha256() {
    }

    /**
     * Hashes the UTF-8 bytes of {@code input}.
     *
     * @param input text to hash
     * @return 64 character lower-case hex digest
     */
    static String hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HEX.formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }
}
