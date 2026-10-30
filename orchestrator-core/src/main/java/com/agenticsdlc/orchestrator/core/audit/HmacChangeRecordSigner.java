package com.agenticsdlc.orchestrator.core.audit;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signs change records with HMAC-SHA256 ({@value #ALGORITHM}, JDK only) and a shared secret key. Signatures are
 * lowercase hex; verification decodes the hex and compares in constant time ({@link MessageDigest#isEqual}).
 *
 * <p>An HMAC proves that the record was finalized by a holder of the key and has not been modified since; anyone who
 * can verify can also sign, so the key must be kept as secret as the signing capability itself. The key is copied
 * on construction and never exposed ({@link #toString()} shows only the key id).</p>
 */
public final class HmacChangeRecordSigner implements ChangeRecordSigner {

    /** The JCA algorithm name, recorded with each signature. */
    public static final String ALGORITHM = "HmacSHA256";

    /** Minimum key length in bytes (the HMAC-SHA256 output size). */
    public static final int MIN_KEY_BYTES = 32;

    private final byte[] key;
    private final String keyId;

    /**
     * Creates the signer.
     *
     * @param key the secret key, at least {@value #MIN_KEY_BYTES} bytes (copied)
     * @param keyId identifier of the key recorded with each signature (not blank; must not reveal the key)
     * @throws IllegalArgumentException when the key is shorter than {@value #MIN_KEY_BYTES} bytes or the key id is
     *                                  blank
     */
    public HmacChangeRecordSigner(byte[] key, String keyId) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(keyId, "keyId");
        if (key.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException("HMAC key must be at least " + MIN_KEY_BYTES + " bytes (got "
                    + key.length + ")");
        }
        if (keyId.isBlank()) {
            throw new IllegalArgumentException("keyId must not be blank");
        }
        this.key = Arrays.copyOf(key, key.length);
        this.keyId = keyId;
    }

    @Override
    public String algorithm() {
        return ALGORITHM;
    }

    @Override
    public String keyId() {
        return keyId;
    }

    /**
     * Returns the HMAC-SHA256 of the UTF-8 bytes of the payload as lowercase hex.
     *
     * @param canonicalPayload the payload
     * @return 64 lowercase hex characters
     */
    @Override
    public String sign(String canonicalPayload) {
        return HexFormat.of().formatHex(mac(canonicalPayload));
    }

    /**
     * Verifies a hex signature in constant time. Malformed hex never verifies.
     *
     * @param canonicalPayload the payload
     * @param signature the hex signature (either case)
     * @return whether it is the HMAC of the payload under this signer's key
     */
    @Override
    public boolean verify(String canonicalPayload, String signature) {
        Objects.requireNonNull(canonicalPayload, "canonicalPayload");
        if (signature == null) {
            return false;
        }
        byte[] provided;
        try {
            provided = HexFormat.of().parseHex(signature);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(mac(canonicalPayload), provided);
    }

    private byte[] mac(String payload) {
        Objects.requireNonNull(payload, "payload");
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(ALGORITHM + " is not available in this JVM", e);
        }
    }

    @Override
    public String toString() {
        return "HmacChangeRecordSigner[keyId=" + keyId + "]";
    }
}
