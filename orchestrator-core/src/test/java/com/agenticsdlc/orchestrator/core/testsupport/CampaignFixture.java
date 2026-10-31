package com.agenticsdlc.orchestrator.core.testsupport;

import java.util.List;

import com.agenticsdlc.orchestrator.core.artifact.ImpactAnalysis;
import com.agenticsdlc.orchestrator.core.artifact.ImpactAnalysis.ComponentType;
import com.agenticsdlc.orchestrator.core.artifact.ImpactAnalysis.ImpactedComponent;
import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec;
import com.agenticsdlc.orchestrator.core.artifact.RiskAssessment;
import com.agenticsdlc.orchestrator.core.artifact.RiskLevel;

/**
 * The campaign-label brownfield requirement over {@link SyntheticWorkspace} (whose {@code domain/Link.java},
 * {@code service/LinkService.java} and {@code api/dto/CreateLinkRequest.java} mirror the real tree), and the
 * impact analysis a model wrote for it in a live run: only migration and configuration files, without change types,
 * entities or endpoints.
 */
public final class CampaignFixture {

    public static final String BASE = "url-shortener/src/main/java/com/agenticsdlc/shortener/";
    public static final String LINK = BASE + "domain/Link.java";
    public static final String LINK_SERVICE = BASE + "service/LinkService.java";
    public static final String LINK_CONTROLLER = BASE + "api/LinkController.java";
    public static final String CREATE_REQUEST = BASE + "api/dto/CreateLinkRequest.java";
    public static final String MIGRATIONS = "url-shortener/src/main/resources/db/migration/";
    public static final String APPLICATION_YML = "url-shortener/src/main/resources/application.yml";

    public static final String REQUIREMENT = "Add an optional campaign label to links created through POST "
            + "/api/v1/links. Store the campaign label in a new column of the links table next to the original URL and "
            + "short code (Flyway migration) and report clicks per campaign through "
            + "GET /api/v1/analytics/campaigns/{campaign}.";

    private CampaignFixture() {
    }

    /** The accepted requirement specification of the campaign requirement. */
    public static RequirementSpec spec() {
        return new RequirementSpec(REQUIREMENT, "Label links with a campaign and report clicks per campaign",
                RequirementSpec.Classification.BROWNFIELD, List.of("Links", "Analytics"), List.of(),
                List.of(new RequirementSpec.Requirement("FR-1", "Store an optional campaign label with each link in "
                                + "a new column of the links table (Flyway migration)", RequirementSpec.Priority.MUST,
                                RequirementSpec.Category.FUNCTIONAL, false),
                        new RequirementSpec.Requirement("FR-2", "Report clicks per campaign through GET "
                                + "/api/v1/analytics/campaigns/{campaign}", RequirementSpec.Priority.MUST,
                                RequirementSpec.Category.FUNCTIONAL, false)),
                List.of(), List.of(new RequirementSpec.AcceptanceCriterion("AC-1", "a link created with a campaign",
                        "clicks are reported for the campaign", "the count includes the link", List.of("FR-1",
                                "FR-2"))), List.of(), List.of(),
                new RequirementSpec.AmbiguityReport(0.0, List.of(), List.of()),
                new RiskAssessment(RiskLevel.LOW, List.of()));
    }

    /**
     * The impact analysis of the live run: two migration files and two configuration files, change types missing
     * (null), no Java type, no entities and no endpoints.
     */
    public static ImpactAnalysis resourcesOnlyImpact() {
        return new ImpactAnalysis("ws", 0, true, List.of(
                new ImpactedComponent("V3 campaign migration", ComponentType.RESOURCE,
                        MIGRATIONS + "V3__add_campaign_label.sql", 0.9, List.of("new column"), null),
                new ImpactedComponent("V1 init migration", null, MIGRATIONS + "V1__init.sql", 0.5,
                        List.of("existing schema"), null),
                new ImpactedComponent("application.yml", ComponentType.CONFIG, APPLICATION_YML, 0.4,
                        List.of("settings"), null),
                new ImpactedComponent("application-test.yml", ComponentType.CONFIG,
                        "url-shortener/src/test/resources/application-test.yml", 0.3, List.of("tests"), null)),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "Campaign labels need a migration.");
    }
}
