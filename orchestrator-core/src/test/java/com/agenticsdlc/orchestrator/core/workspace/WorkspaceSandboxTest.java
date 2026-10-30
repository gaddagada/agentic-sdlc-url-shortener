package com.agenticsdlc.orchestrator.core.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.agent.AgentSettings;
import com.agenticsdlc.orchestrator.core.agent.FailureKind;
import com.agenticsdlc.orchestrator.core.agent.SandboxMaterializer;
import com.agenticsdlc.orchestrator.core.agent.StageContext;
import com.agenticsdlc.orchestrator.core.agent.StageFailure;
import com.agenticsdlc.orchestrator.core.artifact.ChangeSet;
import com.agenticsdlc.orchestrator.core.artifact.ChangeSet.ContentKind;
import com.agenticsdlc.orchestrator.core.artifact.ChangeSet.FileChange;
import com.agenticsdlc.orchestrator.core.artifact.ChangeSet.Operation;
import com.agenticsdlc.orchestrator.core.engine.SandboxCompensationHandler;
import com.agenticsdlc.orchestrator.core.model.Compensation;
import com.agenticsdlc.orchestrator.core.model.CompensationStatus;
import com.agenticsdlc.orchestrator.core.model.RunOptions;
import com.agenticsdlc.orchestrator.core.model.WorkspaceMode;
import com.agenticsdlc.orchestrator.core.testsupport.Contexts;
import com.agenticsdlc.orchestrator.core.testsupport.SyntheticWorkspace;

class WorkspaceSandboxTest {

    @Test
    void copiesTheWorkspaceAppliesChangesAndIsDeletedByTheCompensation(@TempDir Path dir) throws Exception {
        Path workspace = SyntheticWorkspace.create(dir.resolve("ws"));
        Path sandboxRoot = dir.resolve("sandboxes");
        WorkspaceSandbox sandbox = WorkspaceSandbox.create(workspace, sandboxRoot, "run/1");
        assertThat(sandbox.path()).isEqualTo(WorkspaceSandbox.pathFor(sandboxRoot, "run/1"));
        assertThat(sandbox.path().getFileName().toString()).isEqualTo("run_1");
        assertThat(sandbox.path().resolve("url-shortener/src/main/resources/application.yml")).exists();
        assertThat(sandbox.path().resolve("url-shortener/target")).doesNotExist();

        String controller = "url-shortener/src/main/java/com/agenticsdlc/shortener/api/LinkController.java";
        ChangeSet changes = new ChangeSet("HEAD", List.of(
                new FileChange("url-shortener/src/main/java/com/x/New.java", Operation.CREATE, "java", "new",
                        ContentKind.FULL, "class New {}", List.of()),
                new FileChange(controller, Operation.MODIFY, "java", "skeleton", ContentKind.SKELETON, "// sketch",
                        List.of()),
                new FileChange("url-shortener/src/main/resources/application.yml", Operation.DELETE, "yaml", "rm",
                        ContentKind.NONE, "", List.of())), "./mvnw verify", List.of(), "revert", List.of());
        List<String> applied = sandbox.apply(changes);
        assertThat(applied).containsExactly("url-shortener/src/main/java/com/x/New.java", controller + ".skeleton",
                "url-shortener/src/main/resources/application.yml");
        assertThat(sandbox.path().resolve("url-shortener/src/main/java/com/x/New.java")).hasContent("class New {}");
        assertThat(sandbox.path().resolve(controller)).content().contains("public class LinkController");
        assertThat(sandbox.path().resolve(controller + ".skeleton")).hasContent("// sketch");
        assertThat(sandbox.path().resolve("url-shortener/src/main/resources/application.yml")).doesNotExist();
        assertThat(workspace.resolve("url-shortener/src/main/resources/application.yml")).exists();

        new SandboxCompensationHandler(sandboxRoot).compensate(new Compensation("c1", "implement",
                WorkspaceSandbox.DELETE_ACTION, sandbox.path().toString(), "delete", CompensationStatus.REGISTERED,
                java.time.Instant.EPOCH, null, null));
        assertThat(sandbox.path()).doesNotExist();
    }

    @Test
    void rejectsCreateOfAnExistingFileAndPathsOutsideTheWorkloadWithoutWritingAnything(@TempDir Path dir)
            throws Exception {
        Path workspace = SyntheticWorkspace.create(dir.resolve("ws"));
        WorkspaceSandbox sandbox = WorkspaceSandbox.create(workspace, dir.resolve("sandboxes"), "r1");
        String existingTest = "url-shortener/src/test/java/com/agenticsdlc/shortener/api/LinkControllerTest.java";
        String original = Files.readString(sandbox.path().resolve(existingTest));
        ChangeSet changes = new ChangeSet("HEAD", List.of(
                new FileChange("url-shortener/src/main/java/com/x/New.java", Operation.CREATE, "java", "new",
                        ContentKind.FULL, "class New {}", List.of()),
                new FileChange(existingTest, Operation.CREATE, "java", "overwrite", ContentKind.FULL,
                        "class LinkControllerTest {}", List.of()),
                new FileChange("orchestrator-core/src/main/java/Engine.java", Operation.MODIFY, "java", "self",
                        ContentKind.FULL, "class Engine {}", List.of()),
                new FileChange("pom.xml", Operation.MODIFY, "xml", "build", ContentKind.FULL, "<project/>",
                        List.of())), "./mvnw verify", List.of(), "revert", List.of());

        assertThatThrownBy(() -> sandbox.apply(changes)).isInstanceOfSatisfying(ChangeSetRejectedException.class,
                e -> {
                    assertThat(e.problems()).hasSize(3);
                    assertThat(e.problems().get(0)).startsWith("CREATE of existing file " + existingTest)
                            .contains("use MODIFY");
                    assertThat(e.problems().get(1)).startsWith("orchestrator-core/src/main/java/Engine.java is "
                            + "outside the allowed roots");
                    assertThat(e.problems().get(2)).startsWith("pom.xml is outside the allowed roots");
                });
        assertThat(sandbox.path().resolve("url-shortener/src/main/java/com/x/New.java")).as("all or nothing")
                .doesNotExist();
        assertThat(sandbox.path().resolve(existingTest)).hasContent(original);
        assertThat(WorkspaceSandbox.isAllowedPath("./url-shortener//src/A.java")).isTrue();
        assertThat(WorkspaceSandbox.isAllowedPath("docs/adr/0001.md")).isTrue();
        for (String outside : List.of("url-shortener/../pom.xml", "/url-shortener/A.java", "docs/", "README.md",
                "mvnw", ".github/workflows/ci.yml", "orchestrator-app/pom.xml", "URL-SHORTENER/A.java")) {
            assertThat(WorkspaceSandbox.isAllowedPath(outside)).as(outside).isFalse();
        }
    }

    @Test
    void neverCopiesSecretsVcsMetadataBuildOutputOrRuntimeData(@TempDir Path dir) throws Exception {
        Path workspace = SyntheticWorkspace.create(dir.resolve("ws"));
        for (String file : List.of(".env", ".env.local", "url-shortener/.env.production", "certs/server.pem",
                "certs/SERVER.KEY", "url-shortener/src/main/resources/keystore.p12",
                "url-shortener/src/main/resources/truststore.jks", ".git/config", "target/classes/A.class",
                "data/orchestrator.mv.db", "sandbox/old-run/file.txt", ".envrc.d/secret.txt")) {
            Path path = workspace.resolve(file);
            Files.createDirectories(path.getParent());
            Files.writeString(path, "secret");
        }
        Files.writeString(workspace.resolve("url-shortener/src/main/resources/environment.yml"), "env: dev\n");
        WorkspaceSandbox sandbox = WorkspaceSandbox.create(workspace, dir.resolve("sandboxes"), "r1");
        Path copy = sandbox.path();
        for (String skipped : List.of(".env", ".env.local", "url-shortener/.env.production", "certs/server.pem",
                "certs/SERVER.KEY", "url-shortener/src/main/resources/keystore.p12",
                "url-shortener/src/main/resources/truststore.jks", ".git", "target", "data", "sandbox", ".envrc.d")) {
            assertThat(copy.resolve(skipped)).as(skipped).doesNotExist();
        }
        assertThat(copy.resolve("url-shortener/src/main/resources/environment.yml")).exists();
        assertThat(copy.resolve("url-shortener/src/main/resources/application.yml")).exists();
        assertThat(WorkspaceSandbox.isSecretFile("id_rsa.PEM")).isTrue();
        assertThat(WorkspaceSandbox.isSecretFile("keys.md")).isFalse();
        assertThat(WorkspaceSandbox.SKIPPED_DIRECTORIES).contains(".git", "target", "data", "sandbox");
    }

    @Test
    void theMaterializerReportsARejectedChangeSetAsInvalidOutputAndRemovesTheSandbox(@TempDir Path dir)
            throws Exception {
        Path workspace = SyntheticWorkspace.create(dir.resolve("ws"));
        Path sandboxRoot = dir.resolve("sandboxes");
        String existing = "url-shortener/src/main/java/com/agenticsdlc/shortener/api/LinkController.java";
        ChangeSet overwrite = new ChangeSet("HEAD", List.of(new FileChange(existing, Operation.CREATE, "java",
                "rewrite", ContentKind.FULL, "class LinkController {}", List.of())), "./mvnw verify", List.of(),
                "revert", List.of());
        StageContext base = Contexts.of(AgentRole.IMPLEMENTATION, "x");
        StageContext context = new StageContext(base.runId(), base.stageId(), base.role(), base.requirement(),
                base.originalRequirement(), Map.of(), List.of(), base.upstream(), List.of(), 1, 1, null, null,
                RunOptions.defaults().withWorkspace(WorkspaceMode.REPO), List.of(),
                new AgentSettings(0.5, true, false, workspace, sandboxRoot, Duration.ofMinutes(1)));

        assertThatThrownBy(() -> SandboxMaterializer.materialize(overwrite, context))
                .isInstanceOfSatisfying(StageFailure.class, f -> {
                    assertThat(f.kind()).isEqualTo(FailureKind.INVALID_OUTPUT);
                    assertThat(f.getMessage()).startsWith("the workspace sandbox rejected the change set")
                            .contains("CREATE of existing file " + existing);
                });
        assertThat(WorkspaceSandbox.pathFor(sandboxRoot, "run-1")).doesNotExist();
        assertThat(workspace.resolve(existing)).content().contains("public class LinkController");
    }

    private static final String LINK_SERVICE = "url-shortener/src/main/java/com/agenticsdlc/shortener/service/"
            + "LinkService.java";

    /** Adds a field after line 13 of the synthetic LinkService (lines 10 to 14 as context). */
    private static String linkServiceDiff(String thirdContextLine) {
        return "--- a/" + LINK_SERVICE + "\n"
                + "+++ b/" + LINK_SERVICE + "\n"
                + "@@ -10,5 +10,6 @@\n"
                + " public class LinkService {\n"
                + "     private final LinkRepository linkRepository;\n"
                + thirdContextLine + "\n"
                + "     private final ShortCodeGenerator shortCodeGenerator;\n"
                + "+    private final java.time.Clock clock = java.time.Clock.systemUTC();\n"
                + " \n";
    }

    @Test
    void appliesDiffsOverwritesFullContentKeepsSkeletonSidecarsCreatesAndDeletes(@TempDir Path dir) throws Exception {
        Path workspace = SyntheticWorkspace.create(dir.resolve("ws"));
        WorkspaceSandbox sandbox = WorkspaceSandbox.create(workspace, dir.resolve("sandboxes"), "r1");
        String controller = "url-shortener/src/main/java/com/agenticsdlc/shortener/api/LinkController.java";
        String redirect = "url-shortener/src/main/java/com/agenticsdlc/shortener/api/RedirectController.java";
        String created = "url-shortener/src/main/java/com/agenticsdlc/shortener/api/BulkLinkController.java";
        String newDoc = "docs/bulk.md";
        ChangeSet changes = new ChangeSet("HEAD", List.of(
                new FileChange(LINK_SERVICE, Operation.MODIFY, "java", "clock", ContentKind.DIFF,
                        linkServiceDiff("     private final UrlValidator urlValidator;"), List.of()),
                new FileChange(controller, Operation.MODIFY, "java", "rewrite", ContentKind.FULL,
                        "class LinkController {}\n", List.of()),
                new FileChange(redirect, Operation.MODIFY, "java", "sketch", ContentKind.SKELETON, "// sketch",
                        List.of()),
                new FileChange(created, Operation.CREATE, "java", "new", ContentKind.FULL, "class Bulk {}\n",
                        List.of()),
                new FileChange(newDoc, Operation.CREATE, "markdown", "new doc", ContentKind.DIFF,
                        "--- /dev/null\n+++ b/docs/bulk.md\n@@ -0,0 +1,2 @@\n+# Bulk\n+Create many links.\n",
                        List.of()),
                new FileChange("url-shortener/src/main/resources/application.yml", Operation.DELETE, "yaml", "rm",
                        ContentKind.NONE, "", List.of())), "./mvnw verify", List.of(), "revert", List.of());

        List<String> applied = sandbox.apply(changes);

        assertThat(applied).containsExactly(LINK_SERVICE, controller, redirect + ".skeleton", created, newDoc,
                "url-shortener/src/main/resources/application.yml");
        String patched = Files.readString(sandbox.path().resolve(LINK_SERVICE));
        assertThat(patched).contains("    private final ShortCodeGenerator shortCodeGenerator;\n"
                + "    private final java.time.Clock clock = java.time.Clock.systemUTC();\n\n"
                + "    public LinkService(");
        assertThat(patched.lines().count()).isEqualTo(Files.readString(workspace.resolve(LINK_SERVICE)).lines()
                .count() + 1);
        assertThat(sandbox.path().resolve(LINK_SERVICE + ".diff")).as("no sidecar any more").doesNotExist();
        assertThat(sandbox.path().resolve(controller)).hasContent("class LinkController {}");
        assertThat(sandbox.path().resolve(redirect)).content().contains("public class RedirectController");
        assertThat(sandbox.path().resolve(redirect + ".skeleton")).hasContent("// sketch");
        assertThat(sandbox.path().resolve(created)).hasContent("class Bulk {}");
        assertThat(sandbox.path().resolve(newDoc)).hasContent("# Bulk\nCreate many links.");
        assertThat(sandbox.path().resolve("url-shortener/src/main/resources/application.yml")).doesNotExist();
        assertThat(Files.readString(workspace.resolve(LINK_SERVICE))).as("the workspace is never touched")
                .doesNotContain("clock");
    }

    @Test
    void aDiffThatDoesNotApplyRejectsTheChangeSetWithHunkDiagnosticsAndWritesNothing(@TempDir Path dir)
            throws Exception {
        Path workspace = SyntheticWorkspace.create(dir.resolve("ws"));
        WorkspaceSandbox sandbox = WorkspaceSandbox.create(workspace, dir.resolve("sandboxes"), "r1");
        String created = "url-shortener/src/main/java/com/agenticsdlc/shortener/api/BulkLinkController.java";
        String missing = "url-shortener/src/main/java/com/agenticsdlc/shortener/service/Missing.java";
        ChangeSet changes = new ChangeSet("HEAD", List.of(
                new FileChange(created, Operation.CREATE, "java", "new", ContentKind.FULL, "class Bulk {}", List.of()),
                new FileChange(LINK_SERVICE, Operation.MODIFY, "java", "clock", ContentKind.DIFF,
                        linkServiceDiff("     private final LinkLookup lookup;"), List.of()),
                new FileChange(missing, Operation.MODIFY, "java", "edit", ContentKind.DIFF,
                        "@@ -1,1 +1,1 @@\n-a\n+b\n", List.of()),
                new FileChange("docs/notes.md", Operation.MODIFY, "markdown", "edit", ContentKind.DIFF,
                        "just prose, no hunk", List.of())), "./mvnw verify", List.of(), "revert", List.of());

        assertThatThrownBy(() -> sandbox.apply(changes)).isInstanceOfSatisfying(ChangeSetRejectedException.class,
                e -> {
                    assertThat(e.problems()).hasSize(3);
                    assertThat(e.problems().get(0)).startsWith("hunk 1/1 of " + LINK_SERVICE + " (@@ -10,5 +10,6 @@): "
                                    + "context line 'private final LinkLookup lookup;' (hunk line 3) not found in the "
                                    + "file; nearest match at line 12 differs: 'private final UrlValidator "
                                    + "urlValidator;'");
                    assertThat(e.problems().get(1)).startsWith("MODIFY of missing file " + missing)
                            .contains("use CREATE with contentKind FULL");
                    assertThat(e.problems().get(2)).startsWith("DIFF of docs/notes.md cannot be parsed")
                            .contains("no hunk");
                    assertThat(e.getMessage()).startsWith("change set rejected: hunk 1/1 of " + LINK_SERVICE);
                });
        assertThat(sandbox.path().resolve(created)).as("all or nothing").doesNotExist();
        assertThat(Files.readString(sandbox.path().resolve(LINK_SERVICE)))
                .isEqualTo(Files.readString(workspace.resolve(LINK_SERVICE)));
    }

    @Test
    void theMaterializerFeedsHunkDiagnosticsBackAsInvalidOutput(@TempDir Path dir) {
        Path workspace = SyntheticWorkspace.create(dir.resolve("ws"));
        Path sandboxRoot = dir.resolve("sandboxes");
        ChangeSet wrong = new ChangeSet("HEAD", List.of(new FileChange(LINK_SERVICE, Operation.MODIFY, "java",
                "clock", ContentKind.DIFF, linkServiceDiff("     private final LinkLookup lookup;"), List.of())),
                "./mvnw verify", List.of(), "revert", List.of());
        StageContext base = Contexts.of(AgentRole.IMPLEMENTATION, "x");
        StageContext context = new StageContext(base.runId(), base.stageId(), base.role(), base.requirement(),
                base.originalRequirement(), Map.of(), List.of(), base.upstream(), List.of(), 1, 1, null, null,
                RunOptions.defaults().withWorkspace(WorkspaceMode.REPO), List.of(),
                new AgentSettings(0.5, true, false, workspace, sandboxRoot, Duration.ofMinutes(1)));

        assertThatThrownBy(() -> SandboxMaterializer.materialize(wrong, context))
                .isInstanceOfSatisfying(StageFailure.class, f -> {
                    assertThat(f.kind()).isEqualTo(FailureKind.INVALID_OUTPUT);
                    assertThat(f.getMessage()).startsWith("the workspace sandbox rejected the change set: hunk 1/1 of "
                            + LINK_SERVICE).contains("'private final LinkLookup lookup;'")
                            .contains("nearest match at line 12");
                });
        assertThat(WorkspaceSandbox.pathFor(sandboxRoot, "run-1")).doesNotExist();
    }

    @Test
    void refusesPathsOutsideTheSandbox(@TempDir Path dir) throws Exception {
        Path workspace = Files.createDirectories(dir.resolve("ws"));
        WorkspaceSandbox sandbox = WorkspaceSandbox.create(workspace, dir.resolve("sb"), "r1");
        assertThatThrownBy(() -> sandbox.resolveInside("../escape.txt")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkspaceSandbox.delete(dir.resolve("sb"), dir.resolve("ws")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkspaceSandbox.delete(dir.resolve("sb"), dir.resolve("sb")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(workspace).exists();
    }

    @Test
    void processBuildRunnerOnlyAcceptsMavenCommandsInsideTheSandbox(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("mvnw"), "#!/bin/sh\n");
        Files.writeString(dir.resolve("mvnw.cmd"), "@echo off\n");
        assertThat(ProcessBuildRunner.commandLine(dir, "./mvnw -pl url-shortener verify", false))
                .containsExactly("sh", dir.resolve("mvnw").toString(), "-pl", "url-shortener", "verify");
        assertThat(ProcessBuildRunner.commandLine(dir, "mvnw -q verify", true))
                .containsExactly("cmd.exe", "/c", dir.resolve("mvnw.cmd").toString(), "-q", "verify");
        assertThat(ProcessBuildRunner.commandLine(dir, "mvn verify", false)).containsExactly("mvn", "verify");
        for (String bad : List.of("rm -rf /", "./mvnw verify; rm -rf /", "./mvnw verify && curl x", "gradle build",
                "./mvnw -f ../other/pom.xml verify", "mvn -f /etc/pom.xml verify", "")) {
            assertThatThrownBy(() -> ProcessBuildRunner.commandLine(dir, bad, false))
                    .as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> ProcessBuildRunner.commandLine(dir.resolve("missing"), "mvn verify", false))
                .isInstanceOf(IllegalArgumentException.class);
        Path empty = Files.createDirectories(dir.resolve("empty"));
        assertThatThrownBy(() -> ProcessBuildRunner.commandLine(empty, "./mvnw verify", false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("wrapper");
        assertThat(ProcessBuildRunner.parseSurefire("[INFO] Tests run: 12, Failures: 1, Errors: 2, Skipped: 0"))
                .containsExactly(12, 1, 2);
        assertThat(ProcessBuildRunner.parseSurefire("nothing")).isEmpty();
        assertThat(ProcessBuildRunner.isFailureLine("[ERROR] Tests run: 1")).isTrue();
        assertThat(ProcessBuildRunner.isFailureLine("FooTest.bar  Time elapsed: 0.1 s  <<< FAILURE!")).isTrue();
        assertThat(ProcessBuildRunner.isFailureLine("[INFO] BUILD SUCCESS")).isFalse();
        assertThat(BuildResult.notExecuted("mvn", "disabled").executed()).isFalse();
    }
}
