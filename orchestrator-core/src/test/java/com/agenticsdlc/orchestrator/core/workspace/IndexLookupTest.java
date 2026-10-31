package com.agenticsdlc.orchestrator.core.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.agenticsdlc.orchestrator.core.testsupport.SyntheticWorkspace;

/**
 * Names, wrong paths and prose written by agents and reviewers resolve to indexed files, conservatively.
 */
class IndexLookupTest {

    private static final String BASE = "url-shortener/src/main/java/com/agenticsdlc/shortener/";
    private static final String LINK = BASE + "domain/Link.java";
    private static final String LINK_SERVICE = BASE + "service/LinkService.java";
    private static final String CREATE_REQUEST = BASE + "api/dto/CreateLinkRequest.java";
    private static final String LINK_SERVICE_TEST = "url-shortener/src/test/java/com/agenticsdlc/shortener/service/"
            + "LinkServiceTest.java";

    @TempDir
    static Path dir;

    private static CodebaseIndex index;

    @BeforeAll
    static void indexTheWorkspace() {
        index = new CodebaseIndexer().index(SyntheticWorkspace.create(dir.resolve("ws")));
    }

    @Test
    void proseComponentNamesResolveThroughTheLongestTypeNameRun() {
        assertThat(IndexLookup.resolveName(index, "Link entity")).map(IndexedType::path).hasValue(LINK);
        assertThat(IndexLookup.resolveName(index, "the Link entity")).map(IndexedType::path).hasValue(LINK);
        assertThat(IndexLookup.resolveName(index, "Link service (single and bulk create)")).map(IndexedType::path)
                .hasValue(LINK_SERVICE);
        assertThat(IndexLookup.resolveName(index, "CreateLink request/response DTOs and bulk item DTO"))
                .map(IndexedType::path).hasValue(CREATE_REQUEST);
        assertThat(IndexLookup.resolveName(index, "LinkService")).map(IndexedType::path).hasValue(LINK_SERVICE);
        assertThat(IndexLookup.resolveName(index, "LinkServiceTest")).as("only a test type has the name")
                .map(IndexedType::path).hasValue(LINK_SERVICE_TEST);
        assertThat(IndexLookup.resolveName(index, "Campaign analytics endpoint")).isEmpty();
        assertThat(IndexLookup.resolveName(index, " ")).isEmpty();
        assertThat(IndexLookup.resolveName(index, null)).isEmpty();
    }

    @Test
    void pathsResolveBySuffixOrFileNameAndNeverEscapeTheWorkspace() {
        assertThat(IndexLookup.resolvePath(index, LINK)).hasValue(LINK);
        assertThat(IndexLookup.resolvePath(index, "./" + LINK)).hasValue(LINK);
        assertThat(IndexLookup.resolvePath(index, "shortener/domain/Link.java")).hasValue(LINK);
        assertThat(IndexLookup.resolvePath(index, BASE + "link/Link.java")).as("a wrong package resolves by file name")
                .hasValue(LINK);
        assertThat(IndexLookup.resolvePath(index, "V1__init.sql"))
                .hasValue("url-shortener/src/main/resources/db/migration/V1__init.sql");
        assertThat(IndexLookup.resolvePath(index, BASE + "service/CampaignService.java")).isEmpty();
        assertThat(IndexLookup.resolvePath(index, "../outside/Link.java")).isEmpty();
        assertThat(IndexLookup.resolvePath(index, "/etc/Link.java")).isEmpty();
        assertThat(IndexLookup.resolve(index, "", "Link entity")).hasValue(LINK);
        assertThat(IndexLookup.resolve(index, BASE + "link/Link.java", "whatever")).hasValue(LINK);
        assertThat(IndexLookup.resolve(index, BASE + "link/LinkEntity.java", "Link entity"))
                .as("an unknown Java path falls back to the name").hasValue(LINK);
        assertThat(IndexLookup.resolve(index, "LinkService", "LinkService")).as("a bare identifier is a name")
                .hasValue(LINK_SERVICE);
        assertThat(IndexLookup.resolve(index, "docs/links.md", "Link docs"))
                .as("an unknown non-Java path is never replaced by a type found through the name").isEmpty();
    }

    @Test
    void ambiguousNamesResolveToNothing(@TempDir Path other) throws Exception {
        Path ws = SyntheticWorkspace.create(other.resolve("ws"));
        Path second = ws.resolve("url-shortener/src/main/java/com/agenticsdlc/shortener/legacy/Link.java");
        Files.createDirectories(second.getParent());
        Files.writeString(second, "package com.agenticsdlc.shortener.legacy;\n\npublic class Link {\n}\n");
        CodebaseIndex twoLinks = new CodebaseIndexer().index(ws);

        assertThat(twoLinks.typesNamed("Link")).hasSize(2);
        assertThat(IndexLookup.type(twoLinks, "Link")).isEmpty();
        assertThat(IndexLookup.resolveName(twoLinks, "Link entity")).isEmpty();
        assertThat(IndexLookup.resolvePath(twoLinks, "Link.java")).isEmpty();
        assertThat(IndexLookup.mentionedPaths(twoLinks, "Fix the Link entity and LinkService", 12))
                .containsExactly(LINK_SERVICE);
    }

    @Test
    void feedbackNamesTypesByIdentifierFileNameOrPathInOrderOfMention() {
        String feedback = "Add a nullable campaign field to the Link entity and persist it in LinkService.create; "
                + "accept it in CreateLinkRequest. The link must keep working.";

        assertThat(IndexLookup.mentionedPaths(index, feedback, 12)).containsExactly(LINK, LINK_SERVICE,
                CREATE_REQUEST);
        assertThat(IndexLookup.mentionedPaths(index, "Update LinkController.java and " + BASE
                + "link/Link.java; call linkService from the controller", 12)).containsExactly(
                BASE + "api/LinkController.java", LINK, LINK_SERVICE);
        assertThat(IndexLookup.mentionedPaths(index, "the Link service and the short code generator", 12))
                .as("a capitalized word starts a run that completes a type name; lower-case prose names nothing")
                .containsExactly(LINK_SERVICE);
        assertThat(IndexLookup.mentionedPaths(index, "Link.\nService", 12)).as("runs never cross punctuation")
                .containsExactly(LINK);
        assertThat(IndexLookup.mentionedPaths(index, feedback, 2)).containsExactly(LINK, LINK_SERVICE);
        assertThat(IndexLookup.mentionedPaths(index, "Persist the campaign label with the link", 12)).isEmpty();
        assertThat(IndexLookup.mentionedPaths(index, null, 12)).isEmpty();
        assertThat(IndexLookup.mentionedPaths(CodebaseIndex.empty(), feedback, 12)).isEmpty();
    }

    @Test
    void theIndexListsItsFilesAndTypesByName() {
        assertThat(index.files()).contains(LINK, "url-shortener/src/main/resources/application.yml").isSorted();
        assertThat(index.typesNamed("Link")).extracting(IndexedType::path).containsExactly(LINK);
        assertThat(index.typesNamed("Nothing")).isEmpty();
        CodebaseIndex synthetic = new CodebaseIndex("x", null, Instant.EPOCH, List.of(new IndexedType("a.B", "B",
                "m/src/main/java/a/B.java", "m", TypeKind.COMPONENT, List.of(), null, Set.of(), false)), List.of(),
                List.of(), Map.of());
        assertThat(IndexLookup.resolveName(synthetic, "b")).as("a lower-case word is capitalized")
                .map(IndexedType::path).hasValue("m/src/main/java/a/B.java");
    }
}
