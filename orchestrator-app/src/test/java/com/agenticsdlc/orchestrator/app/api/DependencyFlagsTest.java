package com.agenticsdlc.orchestrator.app.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.agenticsdlc.orchestrator.app.api.dto.DependencyView;
import com.agenticsdlc.orchestrator.core.agent.DependencyReview;
import com.agenticsdlc.orchestrator.core.artifact.Check;
import com.agenticsdlc.orchestrator.core.artifact.DependencyRecord;
import com.agenticsdlc.orchestrator.core.artifact.DependencyRecord.Kind;
import com.agenticsdlc.orchestrator.core.artifact.SecurityReview;
import com.agenticsdlc.orchestrator.core.artifact.Severity;

/**
 * {@link DependencyFlags}: every declaration of a security review's inventory carries the hygiene flags of the
 * inventory's rules; SNAPSHOTs only when the review's SNAPSHOT finding names them (workspace modules are not flagged).
 */
class DependencyFlagsTest {

    private static final String POM = "url-shortener/pom.xml";

    @Test
    void declarationsCarryTheirHygieneFlags() {
        List<DependencyRecord> records = List.of(
                new DependencyRecord(POM, "org.example", "clean", "1.2.3", "compile", Kind.DEPENDENCY),
                new DependencyRecord(POM, "org.example", "legacy", "1.0", "system", Kind.DEPENDENCY),
                new DependencyRecord(POM, "org.example", "moving", "LATEST", "compile", Kind.DEPENDENCY),
                new DependencyRecord(POM, "org.example", "ranged", "[1.0,2.0)", "compile", Kind.DEPENDENCY),
                new DependencyRecord(POM, "org.example", "nightly", "2.0-SNAPSHOT", "compile", Kind.DEPENDENCY),
                new DependencyRecord(POM, "com.agenticsdlc", "orchestrator-core", "1.0.0-SNAPSHOT", "compile",
                        Kind.DEPENDENCY),
                new DependencyRecord(POM, "org.example", "twice", "1.0", "compile", Kind.DEPENDENCY),
                new DependencyRecord(POM, "org.example", "twice", "1.1", "compile", Kind.DEPENDENCY),
                new DependencyRecord(POM, "org.apache.maven.plugins", "maven-foo-plugin", DependencyRecord.NONE,
                        "build", Kind.PLUGIN));
        SecurityReview.Finding snapshots = new SecurityReview.Finding(Severity.MEDIUM, DependencyReview.FINDING_PREFIX
                + "SNAPSHOT versions of external artifacts (1)", "Depend on released versions: "
                + "org.example:nightly:2.0-SNAPSHOT in " + POM);
        SecurityReview review = new SecurityReview(List.of(), List.of(Check.pass("dependency-inventory", "ok")),
                List.of(snapshots), List.of(), true, records);

        List<DependencyView> views = DependencyFlags.of(review);

        assertThat(views).hasSize(records.size());
        assertThat(flags(views, "clean")).isEmpty();
        assertThat(flags(views, "legacy")).containsExactly("SYSTEM_SCOPE:HIGH");
        assertThat(flags(views, "moving")).containsExactly("DYNAMIC_VERSION:HIGH");
        assertThat(flags(views, "ranged")).containsExactly("VERSION_RANGE:MEDIUM");
        assertThat(flags(views, "nightly")).containsExactly("SNAPSHOT_VERSION:MEDIUM");
        assertThat(flags(views, "orchestrator-core")).as("a module of the workspace").isEmpty();
        assertThat(flags(views, "twice")).containsExactly("DUPLICATE_DECLARATION:LOW");
        assertThat(flags(views, "maven-foo-plugin")).containsExactly("UNVERSIONED_PLUGIN:LOW");
        assertThat(views.get(0).coordinate()).isEqualTo("org.example:clean:1.2.3");
        assertThat(views.get(0).section()).isEqualTo("dependencies");
        assertThat(views.getLast().kind()).isEqualTo("PLUGIN");
    }

    private static List<String> flags(List<DependencyView> views, String artifactId) {
        return views.stream().filter(v -> v.artifactId().equals(artifactId)).findFirst().orElseThrow().flags()
                .stream().map(f -> f.kind() + ":" + f.severity()).toList();
    }
}
