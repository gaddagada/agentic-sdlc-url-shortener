package com.agenticsdlc.orchestrator.app.startup;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import com.agenticsdlc.orchestrator.app.config.WorkspaceIndexHolder;
import com.agenticsdlc.orchestrator.core.workspace.CodebaseIndexer;

/**
 * {@link WorkspaceIndexHealthIndicator} states.
 */
class WorkspaceIndexHealthIndicatorTest {

    @Test
    void unknownBeforeIndexingThenUpWithCounts(@TempDir Path dir) throws IOException {
        Path source = Files.createDirectories(dir.resolve("svc/src/main/java/demo"));
        Files.writeString(source.resolve("DemoService.java"), "package demo;\n@Service\npublic class DemoService {}\n");
        WorkspaceIndexHolder holder = new WorkspaceIndexHolder(dir, true, new CodebaseIndexer());
        WorkspaceIndexHealthIndicator indicator = new WorkspaceIndexHealthIndicator(holder);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UNKNOWN);

        new WorkspaceIndexWarmup(holder).run(null);
        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("types", 1).containsKey("indexedAt");
    }

    @Test
    void downWhenTheWorkspaceHasNoTypes(@TempDir Path dir) {
        WorkspaceIndexHolder holder = new WorkspaceIndexHolder(dir.resolve("missing"), true, new CodebaseIndexer());
        holder.reindex();

        Health health = new WorkspaceIndexHealthIndicator(holder).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().get("error").toString()).contains("not a directory");
    }

    @Test
    void upWhenWorkspaceIndexingIsDisabled(@TempDir Path dir) {
        WorkspaceIndexHolder holder = new WorkspaceIndexHolder(dir, false, new CodebaseIndexer());

        Health health = new WorkspaceIndexHealthIndicator(holder).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsKey("mode");
    }
}
