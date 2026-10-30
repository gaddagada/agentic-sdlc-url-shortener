package com.agenticsdlc.orchestrator.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import com.agenticsdlc.orchestrator.core.artifact.ReleaseReadiness;
import com.agenticsdlc.orchestrator.core.artifact.RiskLevel;
import com.agenticsdlc.orchestrator.core.json.JsonCodec;

class ChangeRecordSignerTest {

    static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    static final byte[] OTHER_KEY = "fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8);

    private static ReleaseReadiness.ChangeRecord record() {
        return new ReleaseReadiness.ChangeRecord("Add bulk links", RiskLevel.MEDIUM, "Revert the commit",
                List.of(new ReleaseReadiness.ApprovalRecord("design", "alice", "2026-10-30T10:00:00Z", "ok", "ab12"),
                        new ReleaseReadiness.ApprovalRecord("release-readiness", "bob", "2026-10-30T11:00:00Z", "")),
                List.of("artifact-1", "artifact-2"), "REQUIRE_APPROVAL satisfied");
    }

    @Test
    void hmacSignaturesVerifyWithTheSameKeyOnly() {
        HmacChangeRecordSigner signer = new HmacChangeRecordSigner(KEY, "key-2026-10");
        String payload = ChangeRecordSigner.canonicalPayload(record());
        String signature = signer.sign(payload);

        assertThat(signer.algorithm()).isEqualTo("HmacSHA256");
        assertThat(signer.keyId()).isEqualTo("key-2026-10");
        assertThat(signature).hasSize(64).matches("[0-9a-f]+").isEqualTo(signer.sign(payload));
        assertThat(signer.verify(payload, signature)).isTrue();
        assertThat(signer.verify(payload, signature.toUpperCase(Locale.ROOT))).as("hex is case-insensitive").isTrue();
        assertThat(signer.verify(payload + " ", signature)).as("modified payload").isFalse();
        assertThat(signer.verify(payload, "zz")).as("malformed hex").isFalse();
        assertThat(signer.verify(payload, signature.substring(2))).as("truncated").isFalse();
        assertThat(new HmacChangeRecordSigner(OTHER_KEY, "key-2026-10").verify(payload, signature))
                .as("wrong key").isFalse();
    }

    @Test
    void shortKeysAndBlankKeyIdsAreRejectedAndTheKeyIsCopiedAndNeverShown() {
        assertThatThrownBy(() -> new HmacChangeRecordSigner(Arrays.copyOf(KEY, 31), "k"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least 32 bytes");
        assertThatThrownBy(() -> new HmacChangeRecordSigner(KEY, " ")).isInstanceOf(IllegalArgumentException.class);

        byte[] key = Arrays.copyOf(KEY, KEY.length);
        HmacChangeRecordSigner signer = new HmacChangeRecordSigner(key, "k1");
        String before = signer.sign("payload");
        Arrays.fill(key, (byte) 0);
        assertThat(signer.sign("payload")).as("the signer keeps its own copy of the key").isEqualTo(before);
        assertThat(signer.toString()).contains("k1").doesNotContain(new String(KEY, StandardCharsets.UTF_8));
    }

    @Test
    void thePayloadIsTheCanonicalRecordWithoutItsSignature() {
        ReleaseReadiness.ChangeRecord unsigned = record();
        ReleaseReadiness.ChangeRecord signed = unsigned.withSignature(new ReleaseReadiness.Signature("HmacSHA256", "k",
                "00", "2026-10-30T12:00:00Z"));
        String payload = ChangeRecordSigner.canonicalPayload(unsigned);

        assertThat(ChangeRecordSigner.canonicalPayload(signed)).isEqualTo(payload);
        assertThat(payload).doesNotContain("signature").startsWith("{\"approvals\":[{\"approver\":\"alice\"")
                .contains("\"riskLevel\":\"MEDIUM\"");
        ReleaseReadiness.ChangeRecord changed = new ReleaseReadiness.ChangeRecord(unsigned.summary(),
                unsigned.riskLevel(), unsigned.rollbackPlan(), unsigned.approvals().subList(0, 1),
                unsigned.artifactIds(), unsigned.policyVerdict());
        assertThat(ChangeRecordSigner.canonicalPayload(changed)).as("an approval removed").isNotEqualTo(payload);
    }

    @Test
    void recordsVerifyOnlyWithTheirSignersAlgorithmKeyIdAndKey() {
        HmacChangeRecordSigner signer = new HmacChangeRecordSigner(KEY, "k1");
        ReleaseReadiness.ChangeRecord unsigned = record();
        ReleaseReadiness.ChangeRecord signed = unsigned.withSignature(new ReleaseReadiness.Signature(
                signer.algorithm(), signer.keyId(), signer.sign(ChangeRecordSigner.canonicalPayload(unsigned)),
                "2026-10-30T12:00:00Z"));

        assertThat(signer.verify(signed)).isTrue();
        assertThat(signer.verify(unsigned)).as("no signature").isFalse();
        assertThat(new HmacChangeRecordSigner(KEY, "k2").verify(signed)).as("other key id").isFalse();
        assertThat(new HmacChangeRecordSigner(OTHER_KEY, "k1").verify(signed)).as("other key").isFalse();
        ReleaseReadiness.ChangeRecord tampered = new ReleaseReadiness.ChangeRecord("Different summary",
                signed.riskLevel(), signed.rollbackPlan(), signed.approvals(), signed.artifactIds(),
                signed.policyVerdict(), signed.signature());
        assertThat(signer.verify(tampered)).isFalse();
    }

    @Test
    void theUnsignedSignerNeverProducesOrAcceptsASignature() {
        UnsignedChangeRecordSigner signer = UnsignedChangeRecordSigner.INSTANCE;
        assertThat(signer.algorithm()).isEqualTo("none").isEqualTo(UnsignedChangeRecordSigner.ALGORITHM);
        assertThat(signer.keyId()).isEqualTo("none");
        assertThat(signer.sign("payload")).isEmpty();
        assertThat(signer.verify("payload", "")).isFalse();
        assertThat(signer.verify(record().withSignature(new ReleaseReadiness.Signature("none", "none", "",
                "2026-10-30T12:00:00Z")))).isFalse();
    }

    @Test
    void changeRecordsWrittenBeforeSignaturesExistedStillDeserialize() {
        JsonCodec codec = JsonCodec.standard();
        String legacy = "{\"summary\":\"s\",\"riskLevel\":\"LOW\",\"rollbackPlan\":\"r\",\"approvals\":[],"
                + "\"artifactIds\":[\"a\"],\"policyVerdict\":\"v\"}";
        ReleaseReadiness.ChangeRecord parsed = codec.fromJson(legacy, ReleaseReadiness.ChangeRecord.class);
        assertThat(parsed.signature()).isNull();
        assertThat(parsed.artifactIds()).containsExactly("a");

        ReleaseReadiness.ChangeRecord signed = record().withSignature(new ReleaseReadiness.Signature("HmacSHA256",
                "k1", "ab", "2026-10-30T12:00:00Z"));
        assertThat(codec.fromJson(codec.toJson(signed), ReleaseReadiness.ChangeRecord.class)).isEqualTo(signed);
    }
}
