package com.agenticsdlc.orchestrator.app.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.agenticsdlc.orchestrator.app.security.SecuritySliceConfiguration;
import com.agenticsdlc.orchestrator.core.agent.AgentRole;

import com.agenticsdlc.orchestrator.core.artifact.Artifact;
import com.agenticsdlc.orchestrator.core.artifact.ArtifactKind;
import com.agenticsdlc.orchestrator.core.artifact.Provenance;
import com.agenticsdlc.orchestrator.core.artifact.ReleaseReadiness;
import com.agenticsdlc.orchestrator.core.artifact.RiskLevel;
import com.agenticsdlc.orchestrator.core.audit.Actor;
import com.agenticsdlc.orchestrator.core.audit.AuditChain;
import com.agenticsdlc.orchestrator.core.audit.AuditEvent;
import com.agenticsdlc.orchestrator.core.audit.AuditEventType;
import com.agenticsdlc.orchestrator.core.audit.AuditSink;
import com.agenticsdlc.orchestrator.core.audit.ChangeRecordSigner;
import com.agenticsdlc.orchestrator.core.audit.HmacChangeRecordSigner;
import com.agenticsdlc.orchestrator.core.engine.OrchestrationEngine;
import com.agenticsdlc.orchestrator.core.engine.RunNotFoundException;
import com.agenticsdlc.orchestrator.core.graph.WorkflowDefinitions;
import com.agenticsdlc.orchestrator.core.graph.WorkflowGraph;
import com.agenticsdlc.orchestrator.core.json.JsonCodec;
import com.agenticsdlc.orchestrator.core.model.Run;
import com.agenticsdlc.orchestrator.core.model.RunOptions;

/**
 * Web slice of {@link RunArtifactController} (engine and audit sink mocked): the audit chain integrity endpoint, and
 * the change record signature and its verification in the release-readiness artifact view.
 */
@WebMvcTest(RunArtifactController.class)
@Import({ArtifactViewMapper.class, RunArtifactControllerTest.Fixtures.class, SecuritySliceConfiguration.class})
class RunArtifactControllerTest {

    private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");
    private static final WorkflowGraph GRAPH = new WorkflowGraph(WorkflowDefinitions.defaultSdlc());
    private static final HmacChangeRecordSigner SIGNER = new HmacChangeRecordSigner(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8), "k-test");

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private OrchestrationEngine engine;

    @MockitoBean
    private AuditSink auditSink;

    @Test
    void anIntactChainIsReportedValidWithItsLength() {
        Run run = run();
        when(engine.get("run-1")).thenReturn(run);
        when(auditSink.forRun("run-1", 0)).thenReturn(chain(4));

        MvcTestResult result = mvc.get().uri("/api/v1/runs/run-1/audit/integrity").exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.valid").isEqualTo(true);
        assertThat(result).bodyJson().extractingPath("$.length").isEqualTo(4);
        assertThat(result).bodyJson().extractingPath("$.summary").asString().contains("4 events verified");
        assertThat(result).bodyJson().extractingPath("$.brokenAtSeq").isNull();
    }

    @Test
    void aModifiedEventIsReportedWithItsSeqAndHashes() {
        when(engine.get("run-1")).thenReturn(run());
        List<AuditEvent> events = new ArrayList<>(chain(4));
        AuditEvent original = events.get(2);
        events.set(2, new AuditEvent(original.seq(), original.timestamp(), original.runId(), original.stageId(),
                original.type(), original.actor(), "rewritten", original.data(), original.prevHash(),
                original.hash()));
        when(auditSink.forRun("run-1", 0)).thenReturn(events);

        MvcTestResult result = mvc.get().uri("/api/v1/runs/run-1/audit/integrity").exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.valid").isEqualTo(false);
        assertThat(result).bodyJson().extractingPath("$.brokenAtSeq").isEqualTo(3);
        assertThat(result).bodyJson().extractingPath("$.length").isEqualTo(2);
        assertThat(result).bodyJson().extractingPath("$.actualHash").isEqualTo(original.hash());
        assertThat(result).bodyJson().extractingPath("$.expectedHash").asString().hasSize(64);
    }

    @Test
    void theIntegrityOfAnUnknownRunIs404() {
        when(engine.get("missing")).thenThrow(new RunNotFoundException("missing"));

        MvcTestResult result = mvc.get().uri("/api/v1/runs/missing/audit/integrity").exchange();

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.type").isEqualTo(ProblemTypes.RUN_NOT_FOUND.toString());
        verifyNoInteractions(auditSink);
    }

    @Test
    void aSignedChangeRecordIsShownWithItsVerification() {
        ReleaseReadiness.ChangeRecord record = record("alice");
        ReleaseReadiness.ChangeRecord signed = record.withSignature(new ReleaseReadiness.Signature(SIGNER.algorithm(),
                SIGNER.keyId(), SIGNER.sign(ChangeRecordSigner.canonicalPayload(record)), "2026-10-30T10:05:00Z"));
        when(engine.get("run-1")).thenReturn(runWithRelease(signed));

        MvcTestResult result = mvc.get().uri("/api/v1/runs/run-1/artifacts/release-readiness?version=1").exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.signature.algorithm").isEqualTo("HmacSHA256");
        assertThat(result).bodyJson().extractingPath("$.signature.keyId").isEqualTo("k-test");
        assertThat(result).bodyJson().extractingPath("$.signature.value").asString().hasSize(64);
        assertThat(result).bodyJson().extractingPath("$.signature.signedAt").isEqualTo("2026-10-30T10:05:00Z");
        assertThat(result).bodyJson().extractingPath("$.signatureVerified").isEqualTo(true);
    }

    @Test
    void aChangeRecordWhoseApprovalsChangedAfterSigningDoesNotVerify() {
        ReleaseReadiness.ChangeRecord record = record("alice");
        ReleaseReadiness.Signature signature = new ReleaseReadiness.Signature(SIGNER.algorithm(), SIGNER.keyId(),
                SIGNER.sign(ChangeRecordSigner.canonicalPayload(record)), "2026-10-30T10:05:00Z");
        when(engine.get("run-1")).thenReturn(runWithRelease(record("mallory").withSignature(signature)));

        MvcTestResult result = mvc.get().uri("/api/v1/runs/run-1/artifacts/release-readiness?version=1").exchange();

        assertThat(result).bodyJson().extractingPath("$.signature.keyId").isEqualTo("k-test");
        assertThat(result).bodyJson().extractingPath("$.signatureVerified").isEqualTo(false);
    }

    @Test
    void anUnsignedChangeRecordHasNoSignatureAndNoVerification() {
        when(engine.get("run-1")).thenReturn(runWithRelease(record("alice")));

        MvcTestResult result = mvc.get().uri("/api/v1/runs/run-1/artifacts/release-readiness?version=1").exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.signature").isNull();
        assertThat(result).bodyJson().extractingPath("$.signatureVerified").isNull();
    }

    private static Run run() {
        return Run.create("run-1", GRAPH, "custom", "Build a URL shortener with click analytics.",
                RunOptions.defaults(), NOW);
    }

    private static Run runWithRelease(ReleaseReadiness.ChangeRecord record) {
        Run run = run();
        ReleaseReadiness payload = new ReleaseReadiness(List.of(new ReleaseReadiness.ChecklistItem("Verification "
                + "passed", ReleaseReadiness.ChecklistStatus.PASS, "ok")), ReleaseReadiness.GoNoGo.GO, List.of(),
                record, "Release notes");
        JsonCodec codec = JsonCodec.standard();
        run.addArtifact(new Artifact("art-1", "run-1", WorkflowDefinitions.RELEASE_READINESS,
                ArtifactKind.RELEASE_READINESS, 1, 1, codec.hash(payload), payload,
                new Provenance(Map.of(), AgentRole.RELEASE, "engine", "engine", null, null, 0L, 0L, 0L, 1, 1), NOW));
        return run;
    }

    private static ReleaseReadiness.ChangeRecord record(String approver) {
        return new ReleaseReadiness.ChangeRecord("Campaign labels", RiskLevel.MEDIUM, "Revert the commit",
                List.of(new ReleaseReadiness.ApprovalRecord("release-readiness", approver, "2026-10-30T10:04:00Z",
                        "ship it", "")), List.of("art-0"), "approved");
    }

    private static List<AuditEvent> chain(int length) {
        List<AuditEvent> events = new ArrayList<>();
        String previous = null;
        for (int seq = 1; seq <= length; seq++) {
            AuditEvent event = AuditChain.link(new AuditEvent(seq, NOW.plusSeconds(seq), "run-1", null,
                    AuditEventType.STAGE_READY, Actor.system(), "event " + seq, Map.of("seq", seq)), previous);
            events.add(event);
            previous = event.hash();
        }
        return events;
    }

    /**
     * Beans the mapper needs.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {

        @Bean
        JsonCodec jsonCodec() {
            return JsonCodec.standard();
        }

        @Bean
        ChangeRecordSigner changeRecordSigner() {
            return SIGNER;
        }

        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
