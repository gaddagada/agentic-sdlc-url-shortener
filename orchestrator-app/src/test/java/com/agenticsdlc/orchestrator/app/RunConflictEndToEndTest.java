package com.agenticsdlc.orchestrator.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.agenticsdlc.orchestrator.app.api.OperatorIdentityResolver;
import com.agenticsdlc.orchestrator.app.api.ProblemTypes;
import com.agenticsdlc.orchestrator.app.persistence.JpaAuditSink;
import com.agenticsdlc.orchestrator.app.persistence.RunEntity;
import com.agenticsdlc.orchestrator.app.scenario.ScenarioCatalog;
import com.agenticsdlc.orchestrator.core.audit.Actor;
import com.agenticsdlc.orchestrator.core.engine.OrchestrationEngine;
import com.agenticsdlc.orchestrator.core.graph.WorkflowDefinitions;
import com.agenticsdlc.orchestrator.core.model.AutonomyLevel;
import com.agenticsdlc.orchestrator.core.model.RunOptions;
import com.agenticsdlc.orchestrator.core.model.StageStatus;
import com.agenticsdlc.orchestrator.core.model.WorkspaceMode;

/**
 * A command whose write the store refuses because the run changed meanwhile is answered with 409
 * {@code run-conflict} naming the run, never with a 500 or a 503: through the real JPA audit sink when another writer
 * appended to the run's audit trail between this node's read and its commit (the continuity check refuses the batch),
 * and through a sink stub that fails with Spring's optimistic-locking exception. Runs on its own in-memory database.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:run-conflict;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RunConflictEndToEndTest {

    private static final String RUN_CONFLICT = ProblemTypes.BASE + "run-conflict";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private OrchestrationEngine engine;

    @Autowired
    private DataSource dataSource;

    @MockitoSpyBean
    private JpaAuditSink auditSink;

    @Test
    void aCommandOnARunThatAnotherWriterAppendedToIs409RunConflict() {
        String id = runWaitingForTheDesignApproval();
        Map<String, Object> head = JdbcClient.create(dataSource)
                .sql("SELECT seq, hash FROM audit_events WHERE run_id = ? ORDER BY seq DESC LIMIT 1").param(id)
                .query().singleRow();
        long seq = ((Number) head.get("seq")).longValue();
        // another node appends to the trail after this node read the run (and before it commits)
        JdbcClient.create(dataSource).sql("INSERT INTO audit_events (run_id, seq, ts, stage_id, type, actor_type, "
                        + "actor_id, message, data_json, prev_hash, hash) VALUES (?, ?, ?, NULL, 'RUN_INTERRUPTED', "
                        + "'SYSTEM', 'system', 'written by another node', NULL, ?, ?)")
                .params(id, seq + 1, LocalDateTime.now(ZoneOffset.UTC), head.get("hash"), "a".repeat(64)).update();

        MvcTestResult result = approveDesign(id);

        assertRunConflict(result, id);
    }

    @Test
    void anOptimisticLockFailureOfTheStoreIs409RunConflict() {
        String id = runWaitingForTheDesignApproval();
        doThrow(new ObjectOptimisticLockingFailureException(RunEntity.class, id)).when(auditSink).append(anyList());

        MvcTestResult result = approveDesign(id);

        assertRunConflict(result, id);
        assertThat(result).bodyJson().extractingPath("$.detail").asString()
                .doesNotContain("RunEntity").doesNotContain("Row was updated");
    }

    private String runWaitingForTheDesignApproval() {
        String id = engine.start(ScenarioCatalog.GREENFIELD_REQUIREMENT, "greenfield", new RunOptions(
                AutonomyLevel.GUIDED, null, List.of(), null, WorkspaceMode.NONE, Map.of()),
                Actor.human("conflict-operator")).id();
        await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(50)).until(() ->
                engine.get(id).stage(WorkflowDefinitions.DESIGN).status() == StageStatus.AWAITING_APPROVAL);
        return id;
    }

    private MvcTestResult approveDesign(String id) {
        return mvc.post().uri("/api/v1/runs/" + id + "/stages/" + WorkflowDefinitions.DESIGN + "/approve")
                .header(OperatorIdentityResolver.HEADER, "conflict-architect").contentType(MediaType.APPLICATION_JSON)
                .content("{}").exchange();
    }

    private static void assertRunConflict(MvcTestResult result, String id) {
        assertThat(result).hasStatus(HttpStatus.CONFLICT)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.type").isEqualTo(RUN_CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.runId").isEqualTo(id);
        assertThat(result).bodyJson().extractingPath("$.detail").asString().contains(id)
                .contains("another engine node");
    }
}
