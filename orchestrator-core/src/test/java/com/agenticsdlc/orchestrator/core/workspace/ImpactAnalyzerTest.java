package com.agenticsdlc.orchestrator.core.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.agenticsdlc.orchestrator.core.agent.deterministic.RequirementsAgent;
import com.agenticsdlc.orchestrator.core.artifact.ImpactAnalysis;
import com.agenticsdlc.orchestrator.core.artifact.ImpactAnalysis.ChangeNature;
import com.agenticsdlc.orchestrator.core.artifact.ImpactAnalysis.ImpactedComponent;
import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec;
import com.agenticsdlc.orchestrator.core.model.WorkspaceMode;
import com.agenticsdlc.orchestrator.core.testsupport.SyntheticWorkspace;

class ImpactAnalyzerTest {

    private final ImpactAnalyzer analyzer = new ImpactAnalyzer();
    private CodebaseIndex index;

    @BeforeEach
    void index(@TempDir Path dir) throws Exception {
        SyntheticWorkspace.create(dir);
        // an unrelated module that must not be analysed when the requirement names the shortener
        Path other = dir.resolve("orchestrator-core/src/main/java/com/example/JsonCodec.java");
        Files.createDirectories(other.getParent());
        Files.writeString(other, "package com.example;\npublic class JsonCodec { String code; String link; }\n");
        index = new CodebaseIndexer().index(dir);
    }

    private static RequirementSpec spec(String requirement) {
        return new RequirementsAgent().analyze(requirement, List.of(), Map.of(), null, WorkspaceMode.REPO, 0.5);
    }

    private static ImpactedComponent component(ImpactAnalysis analysis, String name) {
        return analysis.components().stream().filter(c -> c.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(name + " not in " + analysis.components()));
    }

    @Test
    void bulkCreationImpactsTheLinksApiAndKeepsAnalyticsAsRegressionRisk() {
        ImpactAnalysis analysis = analyzer.analyze(index, spec("Add a bulk link creation API to the existing URL "
                + "shortener: accept up to 100 URLs per request, reuse the existing URL validation and custom alias "
                + "rules, and respect the existing per-client rate limit. Existing analytics must keep working "
                + "unchanged."));
        analysis.validate();
        assertThat(analysis.existingCodebase()).isTrue();
        assertThat(analysis.summary()).contains("analysed modules: url-shortener");
        assertThat(analysis.components()).noneMatch(c -> c.name().equals("JsonCodec"));

        ImpactedComponent controller = component(analysis, "LinkController");
        assertThat(controller.nature()).isEqualTo(ChangeNature.EXTEND);
        assertThat(controller.confidence()).isGreaterThanOrEqualTo(0.7);
        assertThat(component(analysis, "LinkService").confidence()).isEqualTo(1.0);
        assertThat(controller.reasons()).anySatisfy(r -> assertThat(r).contains("name matches"))
                .anySatisfy(r -> assertThat(r).contains("endpoint POST /api/v1/links"));
        assertThat(component(analysis, "LinkService").nature()).isEqualTo(ChangeNature.EXTEND);
        assertThat(component(analysis, "RateLimitFilter").nature()).isEqualTo(ChangeNature.EXTEND);
        assertThat(component(analysis, "UrlValidator").nature()).isEqualTo(ChangeNature.EXTEND);
        assertThat(component(analysis, "AnalyticsController").nature()).isEqualTo(ChangeNature.REGRESSION_RISK);
        assertThat(component(analysis, "Link").nature()).as("no schema change requested")
                .isEqualTo(ChangeNature.REGRESSION_RISK);
        assertThat(analysis.components().getFirst().nature()).isNotEqualTo(ChangeNature.REGRESSION_RISK);
        assertThat(analysis.endpoints()).extracting(e -> e.method() + " " + e.path())
                .contains("POST /api/v1/links");
        assertThat(analysis.migrations()).isEmpty();
        assertThat(analysis.dataFlows()).anySatisfy(f -> {
            assertThat(f.from()).isEqualTo("LinkController");
            assertThat(f.to()).isEqualTo("links");
            assertThat(f.via()).isEqualTo("LinkService -> LinkRepository");
        });
        assertThat(analysis.riskFactors()).anySatisfy(r -> assertThat(r).startsWith("public API change"))
                .anySatisfy(r -> assertThat(r).startsWith("filter/security change"));
        assertThat(analysis.suggestedTests()).contains(
                "re-run: url-shortener/src/test/java/com/agenticsdlc/shortener/service/LinkServiceTest.java",
                "extend: LinkServiceTest", "extend: LinkControllerTest");
    }

    @Test
    void schemaChangeImpactsEntitiesAndProposesAMigration() {
        ImpactAnalysis analysis = analyzer.analyze(index, spec("Store a title column for every link in the existing "
                + "URL shortener and expose it in the link API with at most 200 characters."));
        assertThat(component(analysis, "Link").nature()).isIn(ChangeNature.MODIFY, ChangeNature.EXTEND);
        assertThat(analysis.entities()).contains("Link (links)");
        assertThat(analysis.migrations()).containsExactly(
                "url-shortener/src/main/resources/db/migration/V2__click_events.sql",
                analysis.migrations().get(1));
        assertThat(analysis.migrations().get(1)).startsWith("proposed: V3__");
        assertThat(analysis.riskFactors()).anySatisfy(r -> assertThat(r).startsWith("schema change"));
    }

    /** Adds a second module with its own entity and a longer migration history than the shortener's. */
    private static CodebaseIndex withSecondMigratingModule(Path dir) throws Exception {
        SyntheticWorkspace.create(dir);
        Path entity = dir.resolve("orchestrator-app/src/main/java/com/example/app/RunEntity.java");
        Files.createDirectories(entity.getParent());
        Files.writeString(entity, """
                package com.example.app;

                import jakarta.persistence.Entity;
                import jakarta.persistence.Table;

                @Entity
                @Table(name = "runs")
                public class RunEntity {
                    private String status;
                }
                """);
        Path migrations = dir.resolve("orchestrator-app/src/main/resources/db/migration");
        Files.createDirectories(migrations);
        for (String name : List.of("V1__runs.sql", "V2__audit.sql", "V7__run_leases.sql")) {
            Files.writeString(migrations.resolve(name), "create table t (id bigint);\n");
        }
        return new CodebaseIndexer().index(dir);
    }

    @Test
    void migrationsAreScopedToTheModuleOfTheImpactedEntities(@TempDir Path dir) throws Exception {
        CodebaseIndex twoModules = withSecondMigratingModule(dir);
        assertThat(twoModules.migrations()).as("the index orders migrations across modules by version")
                .endsWith("orchestrator-app/src/main/resources/db/migration/V7__run_leases.sql");

        ImpactAnalysis named = analyzer.analyze(twoModules, spec("Store a title column for every link in the "
                + "existing URL shortener and expose it in the link API with at most 200 characters."));
        assertThat(named.summary()).contains("analysed modules: url-shortener");
        assertThat(named.migrations()).hasSize(2).first()
                .isEqualTo("url-shortener/src/main/resources/db/migration/V2__click_events.sql");
        assertThat(named.migrations().get(1)).startsWith("proposed: V3__");

        ImpactAnalysis unnamed = analyzer.analyze(twoModules, spec("Store a title column for every link and expose "
                + "it in the link API with at most 200 characters."));
        assertThat(unnamed.summary()).contains("orchestrator-app").contains("url-shortener");
        assertThat(component(unnamed, "Link").nature()).isIn(ChangeNature.MODIFY, ChangeNature.EXTEND);
        assertThat(unnamed.migrations()).as("the impacted entity's module decides, not the whole repository")
                .hasSize(2).first().isEqualTo("url-shortener/src/main/resources/db/migration/V2__click_events.sql");
        assertThat(unnamed.migrations().get(1)).startsWith("proposed: V3__");
    }

    @Test
    void oneHopDependenciesAreRegressionRisksWithReducedConfidence() {
        ImpactAnalysis analysis = analyzer.analyze(index, spec("Extend the redirect of the existing URL shortener to "
                + "resolve codes case-insensitively within 20 ms."));
        ImpactedComponent redirect = component(analysis, "RedirectController");
        assertThat(redirect.nature()).isIn(ChangeNature.MODIFY, ChangeNature.EXTEND);
        ImpactedComponent linkService = component(analysis, "LinkService");
        if (linkService.nature() == ChangeNature.REGRESSION_RISK) {
            assertThat(linkService.confidence()).isLessThan(redirect.confidence());
        }
        assertThat(analysis.components()).filteredOn(c -> c.nature() == ChangeNature.REGRESSION_RISK)
                .allSatisfy(c -> assertThat(c.confidence()).isLessThanOrEqualTo(0.6));
    }

    @Test
    void keywordsAreExpandedWithSynonymsAndSplitIntoChangeAndRegression() {
        ImpactAnalyzer.Keywords keywords = analyzer.keywords(spec("Add custom aliases and expiry to the existing "
                + "URL shortener with 30 days maximum. Existing analytics must keep working unchanged."));
        assertThat(keywords.change()).contains("alias", "code", "slug", "expiry", "ttl", "expires");
        assertThat(keywords.literal()).contains("aliases").doesNotContain("slug");
        assertThat(keywords.regression()).contains("analytics", "click", "referrer");
        assertThat(keywords.addsBehaviour()).isTrue();
        assertThat(keywords.httpMethods()).contains("POST");
    }

    @Test
    void emptyIndexProposesAGreenfieldStructure() {
        ImpactAnalysis analysis = analyzer.analyze(CodebaseIndex.empty(), spec("Build a URL shortener service: "
                + "create short links, redirect with HTTP 302 and record click analytics."));
        analysis.validate();
        assertThat(analysis.existingCodebase()).isFalse();
        assertThat(analysis.workspaceRoot()).isEqualTo("none");
        assertThat(analysis.summary()).startsWith("No existing codebase; greenfield module structure proposed");
        assertThat(analysis.components()).allSatisfy(c -> assertThat(c.nature()).isEqualTo(ChangeNature.NEW))
                .extracting(ImpactedComponent::name).contains("LinkController", "RedirectController", "LinkService",
                        "LinkRepository", "Link", "AnalyticsController", "ClickEvent");
        assertThat(analysis.entities()).containsExactly("Link (links)", "ClickEvent (click_events)");
        assertThat(analysis.riskFactors()).anySatisfy(r -> assertThat(r).contains("SSRF"));
    }
}
