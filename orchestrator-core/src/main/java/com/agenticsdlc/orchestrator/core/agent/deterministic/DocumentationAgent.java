package com.agenticsdlc.orchestrator.core.agent.deterministic;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.agenticsdlc.orchestrator.core.agent.AgentRegistry;
import com.agenticsdlc.orchestrator.core.agent.AgentRole;
import com.agenticsdlc.orchestrator.core.agent.StageAgent;
import com.agenticsdlc.orchestrator.core.agent.StageContext;
import com.agenticsdlc.orchestrator.core.agent.StageFailure;
import com.agenticsdlc.orchestrator.core.agent.StageResult;
import com.agenticsdlc.orchestrator.core.artifact.ChangeSet;
import com.agenticsdlc.orchestrator.core.artifact.DesignSpec;
import com.agenticsdlc.orchestrator.core.artifact.DocumentationBundle;
import com.agenticsdlc.orchestrator.core.artifact.Markdown;
import com.agenticsdlc.orchestrator.core.artifact.RequirementSpec;

/**
 * Deterministic technical writer ({@code document}): README section, API reference table, operations
 * runbook (configuration, health checks, rollback), changelog entry and one ADR per design decision, all
 * generated as Markdown from the design and the change set.
 */
public final class DocumentationAgent implements StageAgent {

    /**
     * Creates the agent.
     */
    public DocumentationAgent() {
        // stateless
    }

    @Override
    public AgentRole role() {
        return AgentRole.DOCUMENTATION;
    }

    @Override
    public String id() {
        return AgentRegistry.DETERMINISTIC;
    }

    @Override
    public StageResult execute(StageContext context) throws StageFailure {
        DocumentationBundle bundle = document(context.require(RequirementSpec.class),
                context.require(DesignSpec.class), context.require(ChangeSet.class));
        return StageResult.success(bundle, "README section, API reference, runbook, changelog and "
                + bundle.adrs().size() + " ADRs generated");
    }

    /**
     * Generates the documentation.
     *
     * @param spec requirement specification
     * @param design design
     * @param changeSet change set
     * @return the bundle
     */
    public DocumentationBundle document(RequirementSpec spec, DesignSpec design, ChangeSet changeSet) {
        Markdown readme = new Markdown().heading(2, spec.intent()).paragraph(spec.normalizedStatement())
                .heading(3, "Capabilities")
                .bullets(spec.functional().stream().map(r -> r.id() + ": " + r.text()).toList())
                .heading(3, "Quality targets")
                .bullets(spec.nonFunctional().stream().map(r -> r.id() + " (" + r.category() + "): " + r.text())
                        .toList())
                .heading(3, "Build and run")
                .code("bash", changeSet.buildCommand() + "\n./mvnw -pl url-shortener spring-boot:run");

        Markdown api = new Markdown().heading(2, "API reference");
        if (design.apiChanges().isEmpty()) {
            api.paragraph("This change does not modify the HTTP API.");
        } else {
            api.table(List.of("Method", "Path", "Summary", "Request", "Response", "Errors"),
                    design.apiChanges().stream().map(a -> List.of(a.method(), "`" + a.path() + "`", a.summary(),
                            a.requestSchema(), a.responseSchema(), String.join("; ", a.errorResponses()))).toList());
            api.paragraph("Errors are returned as RFC 9457 Problem Details (`application/problem+json`).");
        }

        List<String> configuration = new ArrayList<>(List.of("`shortener.base-url`: public base URL of short links",
                "`shortener.code-length`: length of generated codes"));
        String text = String.join(" ", design.securityConsiderations()).toLowerCase(Locale.ROOT)
                + " " + spec.normalizedStatement().toLowerCase(Locale.ROOT);
        if (text.contains("rate limit")) {
            configuration.add("`shortener.rate-limit.capacity` and `shortener.rate-limit.refill-per-minute`: "
                    + "per-client token bucket");
        }
        if (text.contains("private") || text.contains("ssrf")) {
            configuration.add("`shortener.block-private-targets`: SSRF guard for private destinations");
        }
        if (!design.dataHandling().piiFields().isEmpty()) {
            configuration.add("`shortener.analytics.ip-salt`: salt for visitor hashing (rotate regularly; never "
                    + "commit real values)");
        }
        Markdown runbook = new Markdown().heading(2, "Operations runbook")
                .heading(3, "Configuration").bullets(configuration)
                .heading(3, "Health checks")
                .bullets(List.of("`GET /actuator/health` must report `UP`", "`GET /actuator/metrics` exposes request "
                        + "and latency meters"))
                .heading(3, "Monitoring")
                .bullets(design.nfrStrategies().stream().map(n -> n.requirementId() + ": " + n.strategy()).toList())
                .heading(3, "Rollback").paragraph(changeSet.rollbackPlan())
                .heading(3, "Known risks").bullets(changeSet.risks());

        Markdown changelog = new Markdown().heading(3, "Added")
                .bullets(spec.functional().stream().map(r -> r.text() + " (" + r.id() + ")").toList());
        if (!design.dataModelChanges().isEmpty()) {
            changelog.heading(3, "Database").bullets(design.dataModelChanges().stream()
                    .map(d -> d.change() + " (" + d.migration() + ")").toList());
        }

        List<String> adrs = new ArrayList<>();
        int number = 1;
        for (DesignSpec.Decision decision : design.decisions()) {
            adrs.add(new Markdown().heading(3, "ADR-" + number + ": " + decision.title())
                    .field("Status", "Proposed").field("Decision", decision.decision())
                    .field("Rationale", decision.rationale()).render());
            number++;
        }
        return new DocumentationBundle(readme.render(), api.render(), runbook.render(), changelog.render(), adrs);
    }
}
