package com.agenticsdlc.orchestrator.core.artifact;

import java.util.List;
import java.util.Locale;

import com.agenticsdlc.orchestrator.core.util.Immutables;

/**
 * Output of the {@code impact} stage: which parts of the codebase a requirement touches.
 *
 * @param workspaceRoot the indexed workspace name, or {@code "none"}
 * @param indexedFiles number of indexed files
 * @param existingCodebase whether an existing codebase was analysed
 * @param components impacted (or proposed) components
 * @param endpoints impacted HTTP endpoints
 * @param entities impacted entities
 * @param migrations relevant existing migrations and proposed new ones
 * @param dataFlows controller to storage data flows
 * @param riskFactors identified risk factors
 * @param suggestedTests existing tests to re-run and new tests to add
 * @param summary one-paragraph summary
 */
public record ImpactAnalysis(
        String workspaceRoot,
        int indexedFiles,
        boolean existingCodebase,
        List<ImpactedComponent> components,
        List<ImpactedEndpoint> endpoints,
        List<String> entities,
        List<String> migrations,
        List<DataFlow> dataFlows,
        List<String> riskFactors,
        List<String> suggestedTests,
        String summary) implements ArtifactPayload {

    /**
     * Normalizes collections and text.
     */
    public ImpactAnalysis {
        workspaceRoot = Immutables.text(workspaceRoot);
        components = Immutables.list(components);
        endpoints = Immutables.list(endpoints);
        entities = Immutables.list(entities);
        migrations = Immutables.list(migrations);
        dataFlows = Immutables.list(dataFlows);
        riskFactors = Immutables.list(riskFactors);
        suggestedTests = Immutables.list(suggestedTests);
        summary = Immutables.text(summary);
    }

    /** Type of an impacted component. */
    public enum ComponentType {
        /** HTTP controller. */
        CONTROLLER,
        /** Service with business logic. */
        SERVICE,
        /** Persistence repository. */
        REPOSITORY,
        /** Persistent entity. */
        ENTITY,
        /** Configuration class. */
        CONFIG,
        /** Servlet filter. */
        FILTER,
        /** Test class. */
        TEST,
        /** Resource file such as a migration or configuration file. */
        RESOURCE,
        /** Any other type. */
        OTHER
    }

    /** Nature of the impact on a component. */
    public enum ChangeNature {
        /** Existing behaviour changes. */
        MODIFY,
        /** Existing component gains behaviour. */
        EXTEND,
        /** New component. */
        NEW,
        /** Not changed, but may regress (one hop from a changed component). */
        REGRESSION_RISK
    }

    /**
     * An impacted component.
     *
     * @param name simple type name
     * @param type component type
     * @param path repository-relative path (forward slashes)
     * @param confidence 0..1
     * @param reasons why the component is impacted
     * @param nature nature of the impact
     */
    public record ImpactedComponent(String name, ComponentType type, String path, double confidence,
                                    List<String> reasons, ChangeNature nature) {
        /**
         * Normalizes fields.
         */
        public ImpactedComponent {
            name = Immutables.text(name);
            path = Immutables.text(path);
            reasons = Immutables.list(reasons);
        }
    }

    /**
     * An impacted endpoint.
     *
     * @param method HTTP method
     * @param path URL path
     * @param controller controller simple name
     */
    public record ImpactedEndpoint(String method, String path, String controller) {
        /**
         * Normalizes fields.
         */
        public ImpactedEndpoint {
            method = Immutables.text(method);
            path = Immutables.text(path);
            controller = Immutables.text(controller);
        }
    }

    /**
     * A data flow from an entry point to storage.
     *
     * @param from source (for example a controller)
     * @param to sink (for example a table)
     * @param via intermediate hops
     */
    public record DataFlow(String from, String to, String via) {
        /**
         * Normalizes fields.
         */
        public DataFlow {
            from = Immutables.text(from);
            to = Immutables.text(to);
            via = Immutables.text(via);
        }
    }

    @Override
    public ArtifactKind kind() {
        return ArtifactKind.IMPACT_ANALYSIS;
    }

    @Override
    public void validate() {
        Problems p = new Problems("ImpactAnalysis");
        p.requireText(workspaceRoot, "workspaceRoot");
        p.requireText(summary, "summary");
        p.require(indexedFiles >= 0, "indexedFiles must not be negative");
        for (ImpactedComponent c : components) {
            p.requireText(c.name(), "component name");
            p.requireNonNull(c.type(), "component " + c.name() + " type");
            p.require(c.confidence() >= 0.0 && c.confidence() <= 1.0,
                    "component " + c.name() + " confidence must be within 0..1");
            p.requireNonNull(c.nature(), "component " + c.name() + " nature");
        }
        p.require(existingCodebase || components.stream().allMatch(c -> c.nature() == ChangeNature.NEW),
                "without an existing codebase every component must be NEW");
        p.throwIfAny();
    }

    @Override
    public String markdown() {
        Markdown md = new Markdown().heading(2, "Impact analysis")
                .field("Workspace", workspaceRoot)
                .field("Existing codebase", existingCodebase ? "yes (" + indexedFiles + " files indexed)" : "no")
                .paragraph(summary);
        md.heading(3, "Components").table(List.of("Component", "Type", "Nature", "Confidence", "Path", "Reasons"),
                components.stream().map(c -> List.of(c.name(), String.valueOf(c.type()), String.valueOf(c.nature()),
                        String.format(Locale.ROOT, "%.2f", c.confidence()), c.path(),
                        String.join("; ", c.reasons()))).toList());
        md.heading(3, "Endpoints").table(List.of("Method", "Path", "Controller"),
                endpoints.stream().map(e -> List.of(e.method(), e.path(), e.controller())).toList());
        md.heading(3, "Entities").bullets(entities);
        md.heading(3, "Migrations").bullets(migrations);
        md.heading(3, "Data flows").table(List.of("From", "Via", "To"),
                dataFlows.stream().map(f -> List.of(f.from(), f.via(), f.to())).toList());
        md.heading(3, "Risk factors").bullets(riskFactors);
        md.heading(3, "Suggested tests").bullets(suggestedTests);
        return md.render();
    }
}
