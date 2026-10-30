package com.agenticsdlc.orchestrator.core.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.agenticsdlc.orchestrator.core.testsupport.SyntheticWorkspace;

class CodebaseIndexerTest {

    @Test
    void indexesTypesKindsEndpointsTablesReferencesAndResources(@TempDir Path dir) {
        CodebaseIndex index = new CodebaseIndexer().index(SyntheticWorkspace.create(dir));

        assertThat(index.rootName()).isEqualTo(dir.getFileName().toString());
        assertThat(index.find("LinkController")).get().satisfies(t -> {
            assertThat(t.kind()).isEqualTo(TypeKind.CONTROLLER);
            assertThat(t.fqcn()).isEqualTo("com.agenticsdlc.shortener.api.LinkController");
            assertThat(t.module()).isEqualTo("url-shortener");
            assertThat(t.path()).isEqualTo("url-shortener/src/main/java/com/agenticsdlc/shortener/api/LinkController.java");
            assertThat(t.endpoints()).extracting(e -> e.method() + " " + e.path() + " " + e.handler())
                    .containsExactly("POST /api/v1/links create", "GET /api/v1/links/{code} get",
                            "DELETE /api/v1/links/{code} delete");
            assertThat(t.references()).containsExactly("CreateLinkRequest", "LinkResponse", "LinkService");
        });
        assertThat(index.find("RedirectController").orElseThrow().endpoints())
                .extracting(e -> e.method() + " " + e.path()).containsExactly("GET /{code}");
        assertThat(index.find("AnalyticsController").orElseThrow().endpoints())
                .extracting(Endpoint::path).containsExactly("/api/v1/links/{code}/analytics");
        assertThat(index.find("ApiExceptionHandler").orElseThrow().kind()).as("@RestControllerAdvice is not a controller")
                .isEqualTo(TypeKind.COMPONENT);
        assertThat(index.find("LinkService").orElseThrow().kind()).isEqualTo(TypeKind.SERVICE);
        assertThat(index.find("LinkRepository").orElseThrow().kind()).isEqualTo(TypeKind.REPOSITORY);
        assertThat(index.find("Link").orElseThrow()).satisfies(t -> {
            assertThat(t.kind()).isEqualTo(TypeKind.ENTITY);
            assertThat(t.table()).isEqualTo("links");
        });
        assertThat(index.find("ClickEvent").orElseThrow().table()).isEqualTo("click_event");
        assertThat(index.find("ShortenerProperties").orElseThrow().kind()).isEqualTo(TypeKind.CONFIG);
        assertThat(index.find("RateLimitFilter").orElseThrow().kind()).isEqualTo(TypeKind.FILTER);
        assertThat(index.find("UrlValidator").orElseThrow().kind()).isEqualTo(TypeKind.COMPONENT);
        assertThat(index.find("LinkServiceTest").orElseThrow()).satisfies(t -> {
            assertThat(t.kind()).isEqualTo(TypeKind.TEST);
            assertThat(t.test()).isTrue();
        });
        assertThat(index.types()).noneMatch(t -> t.path().contains("/target/"));
        assertThat(index.migrations()).containsExactly(
                "url-shortener/src/main/resources/db/migration/V1__init.sql",
                "url-shortener/src/main/resources/db/migration/V2__click_events.sql");
        assertThat(index.configFiles()).containsExactly("url-shortener/src/main/resources/application.yml");
        assertThat(index.byKind(TypeKind.CONTROLLER)).hasSize(3);
        assertThat(index.dependentsOf("LinkService")).extracting(IndexedType::simpleName)
                .contains("LinkController", "RedirectController", "LinkServiceTest");
        assertThat(index.endpoints()).hasSize(5);
        Map<String, Map<TypeKind, Integer>> summary = index.summary();
        assertThat(summary.get("url-shortener")).containsEntry(TypeKind.CONTROLLER, 3)
                .containsEntry(TypeKind.RESOURCE, 3).containsEntry(TypeKind.TEST, 2);
        assertThat(index.indexedFiles()).isEqualTo(index.types().size() + 3);
        assertThat(index.contentTokens(index.find("LinkService").orElseThrow().path()))
                .contains("link", "service", "linkservice", "validate");
    }

    @Test
    void stripsCommentsButKeepsStringsAndBlanksStringsForClassification() {
        String code = """
                // @RestController in a comment
                /* @Service in a block */
                String s = "http://example.com // not a comment";
                String a = "@Entity";
                """;
        String stripped = CodebaseIndexer.stripComments(code);
        assertThat(stripped).doesNotContain("@RestController").doesNotContain("@Service")
                .contains("http://example.com // not a comment");
        assertThat(CodebaseIndexer.classify(CodebaseIndexer.blankStrings(stripped), false)).isEqualTo(TypeKind.COMPONENT);
        assertThat(CodebaseIndexer.blankStrings("x = \"ab\\\"c\"; y = 'q';")).isEqualTo("x = \"     \"; y = ' ';");
    }

    @Test
    void resolvesMappingPathsFromConstantsAndNormalizesVariables() {
        Map<String, String> constants = Map.of("LinkController.BASE_PATH", "/api/v1/links");
        assertThat(CodebaseIndexer.pathOf("LinkController.BASE_PATH", "Other", constants)).isEqualTo("/api/v1/links");
        assertThat(CodebaseIndexer.pathOf("BASE_PATH", "LinkController", constants)).isEqualTo("/api/v1/links");
        assertThat(CodebaseIndexer.pathOf("value = BASE_PATH + \"/bulk\"", "LinkController", constants))
                .isEqualTo("/api/v1/links/bulk");
        assertThat(CodebaseIndexer.pathOf("path = {\"/a\", \"/b\"}, produces = \"json\"", "X", constants))
                .isEqualTo("/a");
        assertThat(CodebaseIndexer.pathOf("consumes = MediaType.APPLICATION_JSON_VALUE", "X", constants)).isEmpty();
        assertThat(CodebaseIndexer.joinPaths("/api/v1/links/", "/{code:[A-Za-z0-9_-]{4,32}}/analytics"))
                .isEqualTo("/api/v1/links/{code}/analytics");
        assertThat(CodebaseIndexer.joinPaths("", "")).isEqualTo("/");
        assertThat(CodebaseIndexer.flywayVersion("db/migration/V2_1__x.sql")).isEqualTo(List.of(2, 1));
        assertThat(CodebaseIndexer.snakeCase("ClickEvent")).isEqualTo("click_event");
    }

    @Test
    void indexingAnEmptyDirectoryYieldsAnEmptyIndex(@TempDir Path dir) {
        CodebaseIndex index = new CodebaseIndexer().index(dir);
        assertThat(index.isEmpty()).isTrue();
        assertThat(index.indexedFiles()).isZero();
        assertThat(CodebaseIndex.empty().rootName()).isEqualTo("none");
    }
}
