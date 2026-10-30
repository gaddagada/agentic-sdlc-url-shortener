package com.agenticsdlc.orchestrator.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.agenticsdlc.orchestrator.app.persistence.JdbcRunLeaseProvider;
import com.agenticsdlc.orchestrator.app.scenario.ScenarioCatalog;
import com.agenticsdlc.orchestrator.core.audit.Actor;
import com.agenticsdlc.orchestrator.core.engine.OrchestrationEngine;
import com.agenticsdlc.orchestrator.core.graph.WorkflowDefinitions;
import com.agenticsdlc.orchestrator.core.model.AutonomyLevel;
import com.agenticsdlc.orchestrator.core.model.Run;
import com.agenticsdlc.orchestrator.core.model.RunOptions;
import com.agenticsdlc.orchestrator.core.model.RunStatus;
import com.agenticsdlc.orchestrator.core.model.StageStatus;
import com.agenticsdlc.orchestrator.core.model.WorkspaceMode;
import com.agenticsdlc.orchestrator.core.port.RunLease;
import com.agenticsdlc.orchestrator.core.port.RunLeaseProvider;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the persistence layer must deliver on any supported database, run against the whole application context:
 * the deterministic greenfield scenario runs to completion through the engine (approved by two operators), and
 * afterwards the Flyway migrations V1 to V3 are applied, the audit trail read back from the database verifies as an
 * intact hash chain through the integrity endpoint, the run row carries the listing and progress columns, the paged
 * runs list (the projection query with a status and starter filter, ordered by end time) returns it, a lease row
 * existed while the run executed and none is left, and the release change record is signed with the configured key
 * and verifies. Subclasses choose the database: {@code H2PersistenceScenarioTest} (in-memory H2, every build) and
 * {@code PostgresPersistenceIT} (PostgreSQL in a container, where Docker is available).
 *
 * <p>Nothing here depends on wall-clock ordering: progress is awaited by polling the run's state, and the run is
 * identified by its id in a database that may hold runs of other tests.</p>
 */
abstract class PersistenceScenarioContract {

    /** The signing key every subclass configures: base64 of {@code persistence-contract-signing-key} (32 bytes). */
    static final String SIGNING_KEY = "sdlc.governance.signing-key=base64:"
            + "cGVyc2lzdGVuY2UtY29udHJhY3Qtc2lnbmluZy1rZXk=";

    /** The key id every subclass configures. */
    static final String SIGNING_KEY_ID = "sdlc.governance.signing-key-id=contract-key";

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    @Autowired
    private OrchestrationEngine engine;

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JsonMapper json;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Flyway flyway;

    @Autowired
    private RunLeaseProvider leases;

    @Test
    void aDeterministicGreenfieldRunIsPersistedWithItsChainListingLeaseAndSignature() {
        Actor starter = Actor.human("contract-operator");
        Run started = engine.start(ScenarioCatalog.GREENFIELD_REQUIREMENT, "greenfield", new RunOptions(
                AutonomyLevel.GUIDED, null, List.of(), null, WorkspaceMode.NONE, Map.of()), starter);
        String id = started.id();
        awaitRun(id, r -> r.stage(WorkflowDefinitions.DESIGN).status() == StageStatus.AWAITING_APPROVAL);
        engine.approve(id, WorkflowDefinitions.DESIGN, Actor.human("contract-architect"), "design approved");
        awaitRun(id, r -> r.stage(WorkflowDefinitions.RELEASE_READINESS).status() == StageStatus.AWAITING_APPROVAL);
        engine.approve(id, WorkflowDefinitions.RELEASE_READINESS, Actor.human("contract-releaser"), "ship it");
        Run done = awaitRun(id, r -> r.status() == RunStatus.COMPLETED);

        assertThat(flyway.info().applied()).filteredOn(MigrationInfo::isVersioned)
                .extracting(m -> m.getVersion().getVersion()).contains("1", "2", "3");

        JsonNode integrity = get("/api/v1/runs/" + id + "/audit/integrity");
        assertThat(integrity.path("valid").asBoolean()).as(integrity.path("summary").asString()).isTrue();
        assertThat(integrity.path("length").asLong()).isEqualTo(done.auditSeq()).isPositive();

        Map<String, Object> row = JdbcClient.create(dataSource).sql("SELECT status, autonomy, workspace_mode, "
                + "started_by, risk_level, node_id, audit_head_hash, pending_actions, stages_succeeded, stages_total "
                + "FROM runs WHERE id = ?").param(id).query().singleRow();
        assertThat(row.get("status")).isEqualTo("COMPLETED");
        assertThat(row.get("autonomy")).isEqualTo("GUIDED");
        assertThat(row.get("workspace_mode")).isEqualTo("NONE");
        assertThat(row.get("started_by")).isEqualTo("contract-operator");
        assertThat(row.get("risk_level")).isEqualTo(done.currentRisk().level().name());
        assertThat(row.get("node_id")).isNotNull();
        assertThat(row.get("audit_head_hash")).isEqualTo(done.auditHeadHash()).isNotNull();
        assertThat(((Number) row.get("pending_actions")).intValue()).isZero();
        assertThat(((Number) row.get("stages_succeeded")).intValue()).isEqualTo(done.stages().size());
        assertThat(((Number) row.get("stages_total")).intValue()).isEqualTo(done.stages().size());

        JsonNode page = get("/api/v1/runs?status=completed&startedBy=contract-operator&sort=endedAt,desc&size=200");
        assertThat(page.path("total").asLong()).isPositive();
        JsonNode listed = null;
        for (JsonNode item : page.path("items")) {
            if (id.equals(item.path("id").asString())) {
                listed = item;
            }
        }
        assertThat(listed).as("the run in the paged list").isNotNull();
        assertThat(listed.path("status").asString()).isEqualTo("COMPLETED");
        assertThat(listed.path("endedAt").isMissingNode() || listed.path("endedAt").isNull()).isFalse();
        assertThat(listed.path("pendingActions").asInt()).isZero();
        assertThat(listed.path("progress").path("succeeded").asInt()).isEqualTo(done.stages().size());
        assertThat(listed.path("progress").path("total").asInt()).isEqualTo(done.stages().size());

        assertThat(leases).isInstanceOf(RecordingLeases.class);
        RecordingLeases recorded = (RecordingLeases) leases;
        assertThat(recorded.delegate()).isInstanceOf(JdbcRunLeaseProvider.class);
        List<RunLease> granted = recorded.granted(id);
        assertThat(granted).as("leases granted while the run executed").isNotEmpty();
        assertThat(recorded.rowsSeen(id)).as("each grant was a run_leases row").containsAll(
                granted.stream().map(RunLease::token).toList());
        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(recorded.released(id)).containsAll(granted.stream().map(RunLease::token).toList());
            assertThat(JdbcClient.create(dataSource).sql("SELECT COUNT(*) FROM run_leases WHERE run_id = ?")
                    .param(id).query(Long.class).single()).as("no lease row is left").isZero();
        });

        JsonNode release = get("/api/v1/runs/" + id + "/artifacts/release-readiness");
        assertThat(release.path("signature").path("algorithm").asString()).isEqualTo("HmacSHA256");
        assertThat(release.path("signature").path("keyId").asString()).isEqualTo("contract-key");
        assertThat(release.path("signatureVerified").asBoolean()).isTrue();
        assertThat(release.path("payload").path("changeRecord").path("approvals").size()).isEqualTo(2);
    }

    private Run awaitRun(String id, Predicate<Run> condition) {
        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(50)).until(() -> {
            Run run = engine.get(id);
            assertThat(run.status()).as("run %s ended early: %s", id, run.haltDetail())
                    .isNotIn(RunStatus.ABORTED, RunStatus.SAFE_STOPPED);
            return condition.test(run);
        });
        return engine.get(id);
    }

    private JsonNode get(String uri) {
        MvcTestResult result = mvc.get().uri(uri).exchange();
        assertThat(result).hasStatus(HttpStatus.OK);
        return json.readTree(new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8));
    }
}
