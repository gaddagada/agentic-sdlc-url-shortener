package com.agenticsdlc.orchestrator.core.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.agenticsdlc.orchestrator.core.artifact.Artifact;
import com.agenticsdlc.orchestrator.core.model.Run;
import com.agenticsdlc.orchestrator.core.model.RunOptions;
import com.agenticsdlc.orchestrator.core.testsupport.Contexts;
import com.agenticsdlc.orchestrator.core.testsupport.Runs;

class RunEvaluatorTest {

    @BeforeAll
    static void generate() {
        ArtifactScorersTest.generateGoodArtifacts();
    }

    private static Run runWithAllArtifacts() {
        return Runs.withAccepted(Runs.create(ArtifactScorersTest.REQUIREMENT, RunOptions.defaults()),
                ArtifactScorersTest.spec, ArtifactScorersTest.plan, ArtifactScorersTest.impact,
                ArtifactScorersTest.design, ArtifactScorersTest.changeSet, ArtifactScorersTest.testPlan,
                ArtifactScorersTest.verification, ArtifactScorersTest.docs, ArtifactScorersTest.security);
    }

    @Test
    void scoresEveryStageInWorkflowOrderAndAveragesThem() {
        Run run = runWithAllArtifacts();
        Artifact pending = Contexts.artifact(ArtifactScorersTest.release);
        run.addArtifact(pending);

        EvaluationReport report = RunEvaluator.evaluate(run);

        assertThat(report.runId()).isEqualTo("run-1");
        assertThat(report.stageScores().keySet()).containsExactly("intake", "decompose", "impact", "design",
                "implement", "test-design", "verify", "document", "security-scan", "release-readiness");
        assertThat(report.overall()).isCloseTo(report.stageScores().values().stream().mapToDouble(Score::value)
                .average().orElseThrow(), within(1e-12)).isBetween(0.5, 1.0);
        assertThat(report.notes()).containsExactly("release-readiness: scored " + pending.reference()
                + ", which is not accepted yet");
        assertThat(RunEvaluator.evaluate(run)).as("deterministic").isEqualTo(report);

        String markdown = report.markdown();
        assertThat(markdown).contains("## Evaluation of run run-1").contains("| intake |")
                .contains("acceptance-criteria-per-fr").contains("### Notes");
    }

    @Test
    void notesStagesWithoutArtifactsAndScoresAnEmptyRunZero() {
        Run empty = Runs.create("x", RunOptions.defaults());
        EvaluationReport report = RunEvaluator.evaluate(empty, null);
        assertThat(report.stageScores()).isEmpty();
        assertThat(report.overall()).isZero();
        assertThat(report.notes()).singleElement().asString().startsWith("no artifact to evaluate for: intake, ");

        Run partial = Runs.withAccepted(Runs.create("x", RunOptions.defaults()), ArtifactScorersTest.spec);
        assertThat(RunEvaluator.evaluate(partial).stageScores()).containsOnlyKeys("intake");
        assertThat(report.markdown()).contains("Overall score");
    }
}
