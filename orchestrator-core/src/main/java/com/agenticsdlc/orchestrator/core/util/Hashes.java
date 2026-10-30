package com.agenticsdlc.orchestrator.core.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 hashing helpers producing lowercase hexadecimal digests.
 */
public final class Hashes {

    private Hashes() {
    }

    /**
     * Returns the SHA-256 digest of the UTF-8 bytes of {@code text} as lowercase hex.
     *
     * @param text the text to hash
     * @return 64 hex characters
     */
    public static String sha256Hex(String text) {
        return sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Returns the SHA-256 digest of {@code bytes} as lowercase hex.
     *
     * @param bytes the bytes to hash
     * @return 64 hex characters
     */
    public static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available in this JVM", e);
        }
    }
}
