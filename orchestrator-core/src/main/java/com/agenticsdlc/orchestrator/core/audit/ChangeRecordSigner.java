package com.agenticsdlc.orchestrator.core.audit;

import java.util.Map;
import java.util.Objects;

import com.agenticsdlc.orchestrator.core.artifact.ReleaseReadiness;
import com.agenticsdlc.orchestrator.core.json.JsonCodec;

/**
 * Signs finalized release change records (SPI). The engine signs the change record at the moment it finalizes it
 * with the authoritative approvals list (run completion) and stores the result as
 * {@link ReleaseReadiness.ChangeRecord#signature()}; the payload is {@link #canonicalPayload} of the finalized
 * record. Implementations must be thread-safe. {@link #sign} is called while the engine holds the run's lock, so it
 * should be fast (a remote KMS call should have a short timeout); when it throws, the run still completes and the
 * record stays unsigned, with the error in the audit event.
 *
 * <p>Provided implementations: {@link UnsignedChangeRecordSigner} (the default: no signature is attached) and
 * {@link HmacChangeRecordSigner} (HMAC-SHA256 with a shared secret key). Asymmetric signatures (for example a key
 * held in a KMS or HSM) can be plugged in through this interface.</p>
 */
public interface ChangeRecordSigner {

    /**
     * Returns the signature algorithm recorded with each signature ({@code none} for the unsigned signer).
     *
     * @return the algorithm name, for example {@code HmacSHA256}
     */
    String algorithm();

    /**
     * Returns the identifier of the signing key, recorded with each signature so that verifiers can pick the key
     * (supports key rotation). It must never reveal the key.
     *
     * @return the key id
     */
    String keyId();

    /**
     * Signs a canonical payload.
     *
     * @param canonicalPayload the payload ({@link #canonicalPayload(ReleaseReadiness.ChangeRecord)})
     * @return the signature value
     */
    String sign(String canonicalPayload);

    /**
     * Verifies a signature over a canonical payload.
     *
     * @param canonicalPayload the payload
     * @param signature the signature value
     * @return whether the signature is valid for this signer's key
     */
    boolean verify(String canonicalPayload, String signature);

    /**
     * Verifies the signature of a change record: it must be present, name this signer's algorithm and key id, and
     * be valid over {@link #canonicalPayload} of the record.
     *
     * @param record the signed change record
     * @return whether the record carries a valid signature by this signer
     */
    default boolean verify(ReleaseReadiness.ChangeRecord record) {
        Objects.requireNonNull(record, "record");
        ReleaseReadiness.Signature signature = record.signature();
        return signature != null && algorithm().equals(signature.algorithm()) && keyId().equals(signature.keyId())
                && verify(canonicalPayload(record), signature.value());
    }

    /**
     * Returns the payload a change record signature covers: the strict canonical JSON
     * ({@link JsonCodec#strictCanonicalJson(Object)}) of the record without its {@code signature} member. The
     * signature's own {@code signedAt} is therefore not covered.
     *
     * @param record the change record (signed or not; its signature is ignored)
     * @return canonical JSON text
     */
    static String canonicalPayload(ReleaseReadiness.ChangeRecord record) {
        Objects.requireNonNull(record, "record");
        JsonCodec codec = JsonCodec.standard();
        @SuppressWarnings("unchecked")
        Map<String, Object> tree = codec.convert(record.withSignature(null), Map.class);
        tree.remove("signature");
        return codec.strictCanonicalJson(tree);
    }
}
