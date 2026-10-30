package com.agenticsdlc.orchestrator.core.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.agenticsdlc.orchestrator.core.testsupport.SyntheticWorkspace;
import com.agenticsdlc.orchestrator.core.workspace.ImpactedSources.Priority;
import com.agenticsdlc.orchestrator.core.workspace.ImpactedSources.Request;
import com.agenticsdlc.orchestrator.core.workspace.ImpactedSources.Result;

class ImpactedSourcesTest {

    private static final String BASE = "url-shortener/src/main/java/com/agenticsdlc/shortener/";
    private static final String LINK_SERVICE = BASE + "service/LinkService.java";
    private static final String CONTROLLER = BASE + "api/LinkController.java";
    private static final String VALIDATOR = BASE + "service/UrlValidator.java";
    private static final String GENERATOR = BASE + "service/ShortCodeGenerator.java";

    @Test
    void readsSourcesWithinThePerFileLimitAndMarksTruncatedFiles(@TempDir Path dir) throws Exception {
        Path ws = SyntheticWorkspace.create(dir.resolve("ws"));
        String service = Files.readString(ws.resolve(LINK_SERVICE));
        String validator = Files.readString(ws.resolve(VALIDATOR));
        assertThat(service.length()).isGreaterThan(600);
        assertThat(validator.length()).isLessThan(600);

        Result result = ImpactedSources.read(ws, List.of(new Request(LINK_SERVICE, Priority.MODIFY),
                new Request("./" + VALIDATOR, Priority.EXTEND), new Request(LINK_SERVICE, Priority.REGRESSION_RISK)),
                600, 10_000);

        assertThat(result.sources()).containsOnlyKeys(LINK_SERVICE, VALIDATOR);
        assertThat(result.sources().get(VALIDATOR)).isEqualTo(validator);
        String cut = result.sources().get(LINK_SERVICE);
        assertThat(cut).hasSizeLessThanOrEqualTo(600).startsWith("package com.agenticsdlc.shortener.service;")
                .contains(ImpactedSources.TRUNCATED_MARKER).endsWith("; the rest is not shown)\n");
        assertThat(service).startsWith(cut.substring(0, cut.indexOf("\n... [truncated]")));
        assertThat(result.notes()).singleElement().asString()
                .isEqualTo(LINK_SERVICE + ": truncated to 600 of " + service.length() + " characters (per-file limit "
                        + "of 600 characters)");
        assertThat(result.totalChars()).isEqualTo(cut.length() + validator.length());

        Result tiny = ImpactedSources.read(ws, List.of(new Request(LINK_SERVICE, Priority.MODIFY)), 100, 1_000);
        assertThat(tiny.sources().get(LINK_SERVICE)).hasSizeLessThanOrEqualTo(100)
                .startsWith("package com.agenticsdlc.shortener.service;").endsWith("\n[truncated]\n");
    }

    @Test
    void servesModifyTargetsFirstAndAddsRegressionRisksOnlyWhenTheyFit(@TempDir Path dir) throws Exception {
        Path ws = SyntheticWorkspace.create(dir.resolve("ws"));
        int controller = Files.readString(ws.resolve(CONTROLLER)).length();
        int validator = Files.readString(ws.resolve(VALIDATOR)).length();
        int generator = Files.readString(ws.resolve(GENERATOR)).length();
        int total = controller + validator + 10;

        Result result = ImpactedSources.read(ws, List.of(new Request(GENERATOR, Priority.REGRESSION_RISK),
                new Request(VALIDATOR, Priority.EXTEND), new Request(CONTROLLER, Priority.MODIFY)), 20_000, total);

        assertThat(generator).isGreaterThan(10);
        assertThat(result.sources()).containsOnlyKeys(CONTROLLER, VALIDATOR);
        assertThat(result.notes()).singleElement().asString().startsWith(GENERATOR + ": omitted (regression risk; ")
                .endsWith("remaining source budget of 10)");

        Result tight = ImpactedSources.read(ws, List.of(new Request(VALIDATOR, Priority.EXTEND),
                new Request(CONTROLLER, Priority.MODIFY), new Request(LINK_SERVICE, Priority.MODIFY)), 20_000,
                controller + 500);
        assertThat(tight.sources()).containsOnlyKeys(CONTROLLER, LINK_SERVICE);
        assertThat(tight.sources().get(LINK_SERVICE)).hasSizeBetween(250, 500).contains("[truncated]");
        assertThat(tight.totalChars()).isLessThanOrEqualTo(controller + 500);
        assertThat(tight.notes()).containsExactly(
                LINK_SERVICE + ": truncated to 500 of " + Files.readString(ws.resolve(LINK_SERVICE)).length()
                        + " characters (total source budget of " + (controller + 500) + " characters)",
                VALIDATOR + ": omitted (total source budget of " + (controller + 500) + " characters exhausted)");
    }

    @Test
    void refusesPathsOutsideTheRootSecretsAndExcludedDirectoriesAndSkipsBinaries(@TempDir Path dir)
            throws Exception {
        Path ws = SyntheticWorkspace.create(dir.resolve("ws"));
        Files.writeString(dir.resolve("outside.txt"), "top secret");
        Files.createDirectories(ws.resolve(".git"));
        Files.writeString(ws.resolve(".git/config"), "[core]");
        Files.writeString(ws.resolve("url-shortener/.env"), "API_KEY=x");
        Files.writeString(ws.resolve("url-shortener/server.pem"), "-----BEGIN");
        Files.write(ws.resolve("url-shortener/logo.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 0, 1});
        Files.write(ws.resolve("url-shortener/latin1.txt"), new byte[] {'c', 'a', 'f', (byte) 0xE9});

        Result result = ImpactedSources.read(ws, List.of(new Request("../outside.txt", Priority.MODIFY),
                new Request("url-shortener/../../outside.txt", Priority.MODIFY),
                new Request(dir.resolve("outside.txt").toAbsolutePath().toString().replace('\\', '/'),
                        Priority.MODIFY),
                new Request(".git/config", Priority.MODIFY), new Request("url-shortener/.env", Priority.MODIFY),
                new Request("url-shortener/server.pem", Priority.MODIFY),
                new Request("url-shortener/target/classes/Ignored.java", Priority.MODIFY),
                new Request("url-shortener/logo.png", Priority.MODIFY),
                new Request("url-shortener/latin1.txt", Priority.MODIFY),
                new Request("url-shortener/src/main/java/Nope.java", Priority.MODIFY),
                new Request("url-shortener/src", Priority.MODIFY)), 20_000, 120_000);

        assertThat(result.sources()).isEmpty();
        assertThat(result.notes()).hasSize(11);
        assertThat(result.notes().subList(0, 3)).allSatisfy(n -> assertThat(n).endsWith(
                ": refused (outside the workspace root)"));
        assertThat(result.notes()).contains(".git/config: refused (excluded directory .git)",
                "url-shortener/.env: refused (secret-bearing file)",
                "url-shortener/server.pem: refused (secret-bearing file)",
                "url-shortener/target/classes/Ignored.java: refused (excluded directory target)",
                "url-shortener/logo.png: skipped (binary or not UTF-8 text)",
                "url-shortener/latin1.txt: skipped (binary or not UTF-8 text)",
                "url-shortener/src/main/java/Nope.java: not found in the workspace",
                "url-shortener/src: not found in the workspace (not a regular file)");
        assertThat(String.join("\n", result.notes())).doesNotContain("top secret");
        assertThat(ImpactedSources.read(dir.resolve("missing"), List.of(new Request("a.txt", Priority.MODIFY)),
                10, 10).notes()).singleElement().asString().contains("is not readable");
    }
}
