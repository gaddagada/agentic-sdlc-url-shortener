package com.agenticsdlc.orchestrator.app.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.agenticsdlc.orchestrator.core.audit.Actor;

/**
 * {@link OperatorIdentityResolver}: header-asserted identities ({@code open}, {@code api-key}) and verified token
 * identities ({@code oidc}) with an optional, agreeing header.
 */
class OperatorIdentityResolverTest {

    @Test
    void withoutAVerifiedIdentityTheHeaderIsRequiredAndValidated() {
        assertThat(OperatorIdentityResolver.resolve(" alice@example.com ", null))
                .isEqualTo(Actor.human("alice@example.com"));
        assertThatThrownBy(() -> OperatorIdentityResolver.resolve(null, null))
                .isInstanceOf(OperatorIdentityException.class).hasMessageContaining("operator identity required");
        assertThatThrownBy(() -> OperatorIdentityResolver.resolve("alice smith", null))
                .isInstanceOf(OperatorIdentityException.class).hasMessageContaining("X-Operator-Id must match");
        assertThatThrownBy(() -> OperatorIdentityResolver.resolve("Engine", null))
                .isInstanceOf(OperatorIdentityException.class).hasMessageContaining("reserved");
    }

    @Test
    void aVerifiedIdentityNeedsNoHeader() {
        assertThat(OperatorIdentityResolver.resolve(null, "alice")).isEqualTo(Actor.human("alice"));
        assertThat(OperatorIdentityResolver.resolve(" ", "alice")).isEqualTo(Actor.human("alice"));
    }

    @Test
    void anAgreeingHeaderIsAcceptedCaseInsensitivelyAndTheTokenIdentityIsRecorded() {
        assertThat(OperatorIdentityResolver.resolve("ALICE", "alice")).isEqualTo(Actor.human("alice"));
    }

    @Test
    void aDisagreeingHeaderIsAMismatch() {
        assertThatThrownBy(() -> OperatorIdentityResolver.resolve("mallory", "alice"))
                .isInstanceOf(OperatorIdentityMismatchException.class)
                .hasMessageContaining("does not match the authenticated operator 'alice'")
                .hasMessageNotContaining("mallory");
    }

    @Test
    void theTokenIdentityMustBeAValidUnreservedOperatorId() {
        assertThatThrownBy(() -> OperatorIdentityResolver.resolve(null, "system"))
                .isInstanceOf(OperatorIdentityException.class).hasMessageContaining("reserved");
        assertThatThrownBy(() -> OperatorIdentityResolver.resolve(null, "fallback:deterministic"))
                .isInstanceOf(OperatorIdentityException.class).hasMessageContaining("reserved");
        assertThatThrownBy(() -> OperatorIdentityResolver.resolve(null, "Alice Smith"))
                .isInstanceOf(OperatorIdentityException.class).hasMessageContaining("identity claim")
                .isNotInstanceOf(OperatorIdentityMismatchException.class);
    }
}
