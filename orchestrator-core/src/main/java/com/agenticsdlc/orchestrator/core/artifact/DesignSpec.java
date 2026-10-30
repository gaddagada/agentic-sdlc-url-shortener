package com.agenticsdlc.orchestrator.core.artifact;

import java.util.List;

import com.agenticsdlc.orchestrator.core.util.Immutables;

/**
 * Output of the {@code design} stage: the technical design.
 *
 * @param overview design overview
 * @param components components (new or changed)
 * @param apiChanges API changes
 * @param dataModelChanges data model changes
 * @param sequenceFlows textual sequence flows
 * @param nfrStrategies how each NFR is addressed
 * @param securityConsiderations security considerations
 * @param dataHandling personal data handling
 * @param alternativesConsidered alternatives (at least two, exactly one chosen)
 * @param risk design risk assessment
 * @param decisions key decisions (become ADRs)
 */
public record DesignSpec(
        String overview,
        List<Component> components,
        List<ApiChange> apiChanges,
        List<DataModelChange> dataModelChanges,
        List<String> sequenceFlows,
        List<NfrStrategy> nfrStrategies,
        List<String> securityConsiderations,
        DataHandling dataHandling,
        List<Alternative> alternativesConsidered,
        RiskAssessment risk,
        List<Decision> decisions) implements ArtifactPayload {

    /**
     * Normalizes collections and text.
     */
    public DesignSpec {
        overview = Immutables.text(overview);
        components = Immutables.list(components);
        apiChanges = Immutables.list(apiChanges);
        dataModelChanges = Immutables.list(dataModelChanges);
        sequenceFlows = Immutables.list(sequenceFlows);
        nfrStrategies = Immutables.list(nfrStrategies);
        securityConsiderations = Immutables.list(securityConsiderations);
        alternativesConsidered = Immutables.list(alternativesConsidered);
        decisions = Immutables.list(decisions);
    }

    /**
     * A component of the design.
     *
     * @param name component name
     * @param responsibility what it does
     * @param type component type (CONTROLLER, SERVICE, ...)
     * @param isNew whether the component is new
     */
    public record Component(String name, String responsibility, String type, boolean isNew) {
        /**
         * Normalizes text.
         */
        public Component {
            name = Immutables.text(name);
            responsibility = Immutables.text(responsibility);
            type = Immutables.text(type);
        }
    }

    /**
     * An API change.
     *
     * @param method HTTP method
     * @param path URL path
     * @param summary summary
     * @param requestSchema request body description
     * @param responseSchema response body description
     * @param errorResponses error responses
     * @param breaking whether the change breaks existing clients
     */
    public record ApiChange(String method, String path, String summary, String requestSchema, String responseSchema,
                            List<String> errorResponses, boolean breaking) {
        /**
         * Normalizes fields.
         */
        public ApiChange {
            method = Immutables.text(method);
            path = Immutables.text(path);
            summary = Immutables.text(summary);
            requestSchema = Immutables.text(requestSchema);
            responseSchema = Immutables.text(responseSchema);
            errorResponses = Immutables.list(errorResponses);
        }
    }

    /**
     * A data model change.
     *
     * @param entity entity name
     * @param change change description, for example {@code create table links (id, code, ...)}
     * @param migration migration file name
     * @param backwardCompatible whether the change is backward compatible
     */
    public record DataModelChange(String entity, String change, String migration, boolean backwardCompatible) {
        /**
         * Normalizes text.
         */
        public DataModelChange {
            entity = Immutables.text(entity);
            change = Immutables.text(change);
            migration = Immutables.text(migration);
        }
    }

    /**
     * How a non-functional requirement is addressed.
     *
     * @param requirementId the NFR id
     * @param strategy the strategy
     */
    public record NfrStrategy(String requirementId, String strategy) {
        /**
         * Normalizes text.
         */
        public NfrStrategy {
            requirementId = Immutables.text(requirementId);
            strategy = Immutables.text(strategy);
        }
    }

    /**
     * Personal data handling.
     *
     * @param piiFields personal data fields processed
     * @param retention retention period
     * @param protection protection measure (hashing, encryption, ...)
     */
    public record DataHandling(List<String> piiFields, String retention, String protection) {
        /**
         * Normalizes fields.
         */
        public DataHandling {
            piiFields = Immutables.list(piiFields);
            retention = Immutables.text(retention);
            protection = Immutables.text(protection);
        }
    }

    /**
     * An alternative that was considered.
     *
     * @param option the option
     * @param pros advantages
     * @param cons disadvantages
     * @param chosen whether it was chosen
     */
    public record Alternative(String option, List<String> pros, List<String> cons, boolean chosen) {
        /**
         * Normalizes fields.
         */
        public Alternative {
            option = Immutables.text(option);
            pros = Immutables.list(pros);
            cons = Immutables.list(cons);
        }
    }

    /**
     * A design decision.
     *
     * @param title title
     * @param decision the decision
     * @param rationale why
     */
    public record Decision(String title, String decision, String rationale) {
        /**
         * Normalizes text.
         */
        public Decision {
            title = Immutables.text(title);
            decision = Immutables.text(decision);
            rationale = Immutables.text(rationale);
        }
    }

    @Override
    public ArtifactKind kind() {
        return ArtifactKind.DESIGN_SPEC;
    }

    @Override
    public void validate() {
        Problems p = new Problems("DesignSpec");
        p.requireText(overview, "overview");
        p.requireNonEmpty(components, "components");
        p.requireNonNull(dataHandling, "dataHandling");
        p.requireNonNull(risk, "risk");
        p.require(alternativesConsidered.size() >= 2, "at least two alternatives must be considered");
        p.require(alternativesConsidered.stream().filter(Alternative::chosen).count() == 1,
                "exactly one alternative must be chosen");
        for (Component c : components) {
            p.requireText(c.name(), "component name");
            p.requireText(c.type(), "component " + c.name() + " type");
        }
        for (ApiChange a : apiChanges) {
            p.requireText(a.method(), "api change method");
            p.require(a.path().startsWith("/"), "api change path '" + a.path() + "' must start with /");
        }
        for (DataModelChange d : dataModelChanges) {
            p.requireText(d.entity(), "data model change entity");
            p.requireText(d.change(), "data model change " + d.entity() + " change");
        }
        p.throwIfAny();
    }

    @Override
    public String markdown() {
        Markdown md = new Markdown().heading(2, "Design specification").paragraph(overview);
        if (risk != null) {
            md.field("Risk", risk.level() + (risk.factors().isEmpty() ? "" : " (" + String.join(", ", risk.factors())
                    + ")"));
        }
        md.heading(3, "Components").table(List.of("Component", "Type", "New", "Responsibility"),
                components.stream().map(c -> List.of(c.name(), c.type(), c.isNew() ? "yes" : "no",
                        c.responsibility())).toList());
        md.heading(3, "API changes").table(List.of("Method", "Path", "Summary", "Request", "Response", "Errors",
                        "Breaking"),
                apiChanges.stream().map(a -> List.of(a.method(), a.path(), a.summary(), a.requestSchema(),
                        a.responseSchema(), Markdown.join(a.errorResponses()), a.breaking() ? "yes" : "no")).toList());
        md.heading(3, "Data model changes").table(List.of("Entity", "Change", "Migration", "Backward compatible"),
                dataModelChanges.stream().map(d -> List.of(d.entity(), d.change(), d.migration(),
                        d.backwardCompatible() ? "yes" : "no")).toList());
        md.heading(3, "Sequence flows").bullets(sequenceFlows);
        md.heading(3, "NFR strategies").table(List.of("Requirement", "Strategy"),
                nfrStrategies.stream().map(n -> List.of(n.requirementId(), n.strategy())).toList());
        md.heading(3, "Security considerations").bullets(securityConsiderations);
        if (dataHandling != null) {
            md.heading(3, "Data handling")
                    .field("PII fields", Markdown.join(dataHandling.piiFields()))
                    .field("Retention", dataHandling.retention().isBlank() ? "-" : dataHandling.retention())
                    .field("Protection", dataHandling.protection().isBlank() ? "-" : dataHandling.protection());
        }
        md.heading(3, "Alternatives considered").table(List.of("Option", "Chosen", "Pros", "Cons"),
                alternativesConsidered.stream().map(a -> List.of(a.option(), a.chosen() ? "yes" : "no",
                        String.join("; ", a.pros()), String.join("; ", a.cons()))).toList());
        md.heading(3, "Decisions").table(List.of("Decision", "Outcome", "Rationale"),
                decisions.stream().map(d -> List.of(d.title(), d.decision(), d.rationale())).toList());
        return md.render();
    }
}
