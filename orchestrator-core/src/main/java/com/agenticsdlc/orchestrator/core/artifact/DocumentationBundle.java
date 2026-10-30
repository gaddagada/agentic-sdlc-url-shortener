package com.agenticsdlc.orchestrator.core.artifact;

import java.util.List;

import com.agenticsdlc.orchestrator.core.util.Immutables;

/**
 * Output of the {@code document} stage: Markdown documentation fragments.
 *
 * @param readmeSection README section
 * @param apiReference API reference
 * @param operationsRunbook operations runbook
 * @param changelogEntry changelog entry
 * @param adrs architecture decision records (Markdown, one per decision)
 */
public record DocumentationBundle(String readmeSection, String apiReference, String operationsRunbook,
                                  String changelogEntry, List<String> adrs) implements ArtifactPayload {

    /**
     * Normalizes fields.
     */
    public DocumentationBundle {
        readmeSection = Immutables.text(readmeSection);
        apiReference = Immutables.text(apiReference);
        operationsRunbook = Immutables.text(operationsRunbook);
        changelogEntry = Immutables.text(changelogEntry);
        adrs = Immutables.list(adrs);
    }

    @Override
    public ArtifactKind kind() {
        return ArtifactKind.DOCUMENTATION_BUNDLE;
    }

    @Override
    public void validate() {
        Problems p = new Problems("DocumentationBundle");
        p.requireText(readmeSection, "readmeSection");
        p.requireText(apiReference, "apiReference");
        p.requireText(operationsRunbook, "operationsRunbook");
        p.requireText(changelogEntry, "changelogEntry");
        p.throwIfAny();
    }

    @Override
    public String markdown() {
        Markdown md = new Markdown().heading(2, "Documentation bundle");
        md.heading(3, "README section").raw(readmeSection.strip() + "\n\n");
        md.heading(3, "API reference").raw(apiReference.strip() + "\n\n");
        md.heading(3, "Operations runbook").raw(operationsRunbook.strip() + "\n\n");
        md.heading(3, "Changelog entry").raw(changelogEntry.strip() + "\n\n");
        md.heading(3, "ADRs");
        if (adrs.isEmpty()) {
            md.paragraph("_none_");
        }
        for (String adr : adrs) {
            md.raw(adr.strip() + "\n\n");
        }
        return md.render();
    }
}
