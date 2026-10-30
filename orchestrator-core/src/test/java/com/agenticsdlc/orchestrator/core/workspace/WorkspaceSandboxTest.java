package com.agenticsdlc.orchestrator.core.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.agenticsdlc.orchestrator.core.artifact.ChangeSet;
import com.agenticsdlc.orchestrator.core.artifact.ChangeSet.ContentKind;
import com.agenticsdlc.orchestrator.core.artifact.ChangeSet.FileChange;
import com.agenticsdlc.orchestrator.core.artifact.ChangeSet.Operation;
import com.agenticsdlc.orchestrator.core.engine.SandboxCompensationHandler;
import com.agenticsdlc.orchestrator.core.model.Compensation;
import com.agenticsdlc.orchestrator.core.model.CompensationStatus;
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
