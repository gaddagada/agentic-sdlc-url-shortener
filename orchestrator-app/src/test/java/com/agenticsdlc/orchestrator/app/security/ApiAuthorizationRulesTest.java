package com.agenticsdlc.orchestrator.app.security;

import static com.agenticsdlc.orchestrator.app.security.ControlPlaneRole.ADMIN;
import static com.agenticsdlc.orchestrator.app.security.ControlPlaneRole.APPROVER;
import static com.agenticsdlc.orchestrator.app.security.ControlPlaneRole.OPERATOR;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * {@link ApiAuthorizationRules}: the role each governance command needs, reads by any role, and fail-closed ADMIN
 * for unlisted mutating API requests.
 */
class ApiAuthorizationRulesTest {

    @Test
    void governanceCommandsNeedTheirRole() {
        for (String path : List.of("/api/v1/runs", "/api/v1/runs/r1/clarify", "/api/v1/runs/r1/replan",
                "/api/v1/runs/r1/stop", "/api/v1/runs/r1/resume", "/api/v1/scenarios/greenfield/run")) {
            assertThat(ApiAuthorizationRules.allowedRoles("POST", path)).as(path).containsExactly(OPERATOR);
        }
        assertThat(ApiAuthorizationRules.allowedRoles("POST", "/api/v1/runs/r1/stages/design/approve"))
                .containsExactly(APPROVER);
        assertThat(ApiAuthorizationRules.allowedRoles("post", "/api/v1/runs/r1/stages/release-readiness/reject"))
                .containsExactly(APPROVER);
        assertThat(ApiAuthorizationRules.allowedRoles("POST", "/api/v1/workspace/reindex")).containsExactly(ADMIN);
    }

    @Test
    void readsNeedAnyRoleAndOtherApiWritesNeedAdmin() {
        assertThat(ApiAuthorizationRules.allowedRoles("GET", "/api/v1/runs/r1/audit"))
                .containsExactly(OPERATOR, APPROVER, ADMIN);
        assertThat(ApiAuthorizationRules.allowedRoles("GET", "/api/v1/runs/r1/stages/design/approve"))
                .containsExactly(OPERATOR, APPROVER, ADMIN);
        assertThat(ApiAuthorizationRules.allowedRoles("DELETE", "/api/v1/runs/r1")).containsExactly(ADMIN);
        assertThat(ApiAuthorizationRules.allowedRoles("POST", "/api/v1/runs/r1/stages/design/approve/extra"))
                .containsExactly(ADMIN);
        assertThat(ApiAuthorizationRules.allowedRoles("POST", "/actuator/metrics"))
                .containsExactly(OPERATOR, APPROVER, ADMIN);
    }

    @Test
    void encodedPathsAreMatchedDecoded() {
        assertThat(ApiAuthorizationRules.allowedRoles("POST", "/%61pi/v1/workspace/reindex")).containsExactly(ADMIN);
    }
}
