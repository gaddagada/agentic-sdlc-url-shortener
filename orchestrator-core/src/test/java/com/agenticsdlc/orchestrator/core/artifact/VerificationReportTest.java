package com.agenticsdlc.orchestrator.core.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.agenticsdlc.orchestrator.core.json.JsonCodec;

/**
 * Blocking and advisory verification findings.
 */
class VerificationReportTest {

    private static final VerificationReport.Traceability COVERED = new VerificationReport.Traceability(2, 2,
            List.of());
    private static final Check PLACEMENT = Check.advisory("test-placement", false,
            "PerformanceTest runs in the default build");

    @Test
    void advisoryFailuresDoNotFailVerificationButBlockingFailuresAndUncoveredRequirementsDo() {
        List<Check> advisoryOnly = List.of(Check.pass("tests-are-not-vacuous", "ok"), PLACEMENT);
        assertThat(VerificationReport.computePassed(advisoryOnly, COVERED, null)).isTrue();

        VerificationReport passed = new VerificationReport(advisoryOnly, COVERED, null, true,
                List.of("Advisory: move PerformanceTest to the perf profile"));
        passed.validate();
        assertThat(passed.advisoryFindings()).containsExactly("test-placement: PerformanceTest runs in the default "
                + "build");
        assertThat(passed.blockingFailures()).isEmpty();
        assertThat(passed.markdown()).contains("PASSED").contains("Advisory findings (do not fail verification)")
                .contains("advisory finding").contains("test-placement: PerformanceTest runs in the default build");

        VerificationReport inconsistent = new VerificationReport(advisoryOnly, COVERED, null, false,
                List.of("Advisory: move PerformanceTest"));
        assertThatThrownBy(inconsistent::validate).isInstanceOf(InvalidArtifactException.class)
                .hasMessageContaining("passed flag is inconsistent");

        List<Check> blocking = List.of(Check.fail("tests-are-not-vacuous", "no assertion in FooTest"), PLACEMENT);
        assertThat(VerificationReport.computePassed(blocking, COVERED, null)).isFalse();
        VerificationReport failed = new VerificationReport(blocking, COVERED, null, false,
                List.of("Add assertions to FooTest"));
        failed.validate();
        assertThat(failed.blockingFailures()).containsExactly("tests-are-not-vacuous");
        assertThat(failed.markdown()).contains("FAILED").contains("| FAIL |");

        VerificationReport.Traceability uncovered = new VerificationReport.Traceability(2, 1, List.of("FR-2"));
        assertThat(VerificationReport.computePassed(advisoryOnly, uncovered, null)).isFalse();
        VerificationReport.BuildOutcome broken = new VerificationReport.BuildOutcome(true, "mvn", 1, 1, "failed",
                List.of());
        assertThat(VerificationReport.computePassed(advisoryOnly, COVERED, broken)).isFalse();
    }

    @Test
    void checksWithoutTheFlagAreBlockingAndOnlyTheFlagIsSerialized() {
        JsonCodec codec = JsonCodec.standard();
        Check legacy = codec.fromJson("{\"name\":\"no-secrets\",\"passed\":false,\"details\":\"d\"}", Check.class);
        assertThat(legacy.blocking()).isTrue();
        assertThat(legacy.failsBlocking()).isTrue();
        assertThat(new Check("x", false, "d").blocking()).isTrue();
        assertThat(new Check("x", false, "d", null).blocking()).isTrue();
        assertThat(Check.pass("x", "d").blocking()).isTrue();
        assertThat(Check.fail("x", "d").blocking()).isTrue();
        assertThat(PLACEMENT.blocking()).isFalse();
        assertThat(PLACEMENT.failsBlocking()).isFalse();

        VerificationReport report = new VerificationReport(List.of(Check.pass("a", "ok"), PLACEMENT), COVERED, null,
                true, List.of());
        String json = codec.toJson(report);
        assertThat(json).contains("\"blocking\":false").contains("\"blocking\":true")
                .doesNotContain("advisoryFindings").doesNotContain("blockingFailures").doesNotContain("failsBlocking");
        assertThat(codec.fromJson(json, VerificationReport.class)).isEqualTo(report);
    }
}
