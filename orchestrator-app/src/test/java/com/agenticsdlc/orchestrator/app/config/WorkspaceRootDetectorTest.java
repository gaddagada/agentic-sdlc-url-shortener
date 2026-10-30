package com.agenticsdlc.orchestrator.app.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.agenticsdlc.orchestrator.core.workspace.CodebaseIndexer;

/**
 * {@link WorkspaceRootDetector} and {@link WorkspaceIndexHolder}.
 */
class WorkspaceRootDetectorTest {

    @Test
    void walksUpToTheDirectoryWithPomAndUrlShortener(@TempDir Path dir) throws IOException {
        Path root = dir.resolve("repo");
        Files.createDirectories(root.resolve("url-shortener"));
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Path module = Files.createDirectories(root.resolve("orchestrator-app/target"));
        Files.writeString(root.resolve("orchestrator-app/pom.xml"), "<project/>");

        assertThat(WorkspaceRootDetector.detect(module)).isEqualTo(root.toAbsolutePath().normalize());
        assertThat(WorkspaceRootDetector.detect(root)).isEqualTo(root.toAbsolutePath().normalize());
    }

    @Test
    void fallsBackToTheStartDirectory(@TempDir Path dir) {
        assertThat(WorkspaceRootDetector.detect(dir)).isEqualTo(dir.toAbsolutePath().normalize());
    }

    @Test
    void theRepositoryRunningTheseTestsIsDetected() {
        Path root = WorkspaceRootDetector.detect(Path.of("").toAbsolutePath());

        assertThat(root.resolve("url-shortener")).isDirectory();
        assertThat(root.resolve("pom.xml")).isRegularFile();
    }

    @Test
    void theHolderIndexesOnDemandAndReportsFailures(@TempDir Path dir) throws IOException {
        Path source = Files.createDirectories(dir.resolve("svc/src/main/java/demo"));
        Files.writeString(source.resolve("HelloController.java"), """
                package demo;
                @RestController
                @RequestMapping("/hello")
                public class HelloController {
                    @GetMapping
                    public String hello() { return "hi"; }
                }
                """);
        WorkspaceIndexHolder holder = new WorkspaceIndexHolder(dir, true, new CodebaseIndexer());
        assertThat(holder.current()).isNull();

        assertThat(holder.reindex().types()).hasSize(1);
        assertThat(holder.current()).isNotNull();
        assertThat(holder.lastError()).isNull();

        WorkspaceIndexHolder missing = new WorkspaceIndexHolder(dir.resolve("absent"), true, new CodebaseIndexer());
        assertThat(missing.reindex().isEmpty()).isTrue();
        assertThat(missing.lastError()).contains("not a directory");

        WorkspaceIndexHolder disabled = new WorkspaceIndexHolder(dir, false, new CodebaseIndexer());
        disabled.reindex();
        assertThat(disabled.current()).isNull();
    }
}
