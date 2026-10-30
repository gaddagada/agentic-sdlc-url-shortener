package com.agenticsdlc.orchestrator.core.audit;

/**
 * The default {@link ChangeRecordSigner}: change records are not signed. The engine attaches no signature when this
 * signer is configured (the record's {@code signature} stays null), and {@link #verify(String, String)} never
 * accepts anything, so an unsigned record can never pass as signed (no "alg=none" acceptance).
 */
public final class UnsignedChangeRecordSigner implements ChangeRecordSigner {

    /** The algorithm name of this signer. */
    public static final String ALGORITHM = "none";

    /** The shared instance. */
    public static final UnsignedChangeRecordSigner INSTANCE = new UnsignedChangeRecordSigner();

    /**
     * Creates an unsigned signer ({@link #INSTANCE} can be shared).
     */
    public UnsignedChangeRecordSigner() {
        // stateless
    }

    /**
     * Returns {@value #ALGORITHM}.
     *
     * @return {@value #ALGORITHM}
     */
    @Override
    public String algorithm() {
        return ALGORITHM;
    }

    /**
     * Returns {@value #ALGORITHM} (there is no key).
     *
     * @return {@value #ALGORITHM}
     */
    @Override
    public String keyId() {
        return ALGORITHM;
    }

    /**
     * Returns the empty signature.
     *
     * @param canonicalPayload ignored
     * @return the empty string
     */
    @Override
    public String sign(String canonicalPayload) {
        return "";
    }

    /**
     * Never verifies.
     *
     * @param canonicalPayload ignored
     * @param signature ignored
     * @return false
     */
    @Override
    public boolean verify(String canonicalPayload, String signature) {
        return false;
    }

    @Override
    public String toString() {
        return "UnsignedChangeRecordSigner";
    }
}
