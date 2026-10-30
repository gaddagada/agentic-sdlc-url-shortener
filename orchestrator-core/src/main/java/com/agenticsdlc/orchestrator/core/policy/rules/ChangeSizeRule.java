package com.agenticsdlc.orchestrator.core.policy.rules;

import java.util.Optional;

import com.agenticsdlc.orchestrator.core.artifact.ChangeSet;
import com.agenticsdlc.orchestrator.core.artifact.Severity;
import com.agenticsdlc.orchestrator.core.policy.PolicyContext;
import com.agenticsdlc.orchestrator.core.policy.PolicyFinding;
import com.agenticsdlc.orchestrator.core.policy.PolicyRule;
import com.agenticsdlc.orchestrator.core.policy.Verdict;

/**
 * {@code CHG-001 ChangeSize} (EXIT of the implementation stage): requires approval for large changes
 * (more than 25 files or more than 1500 content lines by default).
 */
public final class ChangeSizeRule implements PolicyRule {

    /** Rule id. */
    public static final String ID = "CHG-001";

    /** Default maximum number of files. */
    public static final int DEFAULT_MAX_FILES = 25;

    /** Default maximum number of content lines. */
    public static final int DEFAULT_MAX_LINES = 1500;

    private final int maxFiles;
    private final int maxLines;

    /**
     * Creates the rule with the default limits.
     */
    public ChangeSizeRule() {
        this(DEFAULT_MAX_FILES, DEFAULT_MAX_LINES);
    }

    /**
     * Creates the rule.
     *
     * @param maxFiles maximum files before approval is required
     * @param maxLines maximum content lines before approval is required
     */
    public ChangeSizeRule(int maxFiles, int maxLines) {
        this.maxFiles = maxFiles;
        this.maxLines = maxLines;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "ChangeSize";
    }

    @Override
    public Optional<PolicyFinding> evaluate(PolicyContext ctx) {
        ChangeSet changeSet = ctx.candidate(ChangeSet.class);
        if (changeSet == null) {
            return Optional.empty();
        }
        int files = changeSet.changes().size();
        int lines = changeSet.totalContentLines();
        if (files <= maxFiles && lines <= maxLines) {
            return Optional.empty();
        }
        return Optional.of(new PolicyFinding(ID, Severity.MEDIUM, Verdict.REQUIRE_APPROVAL,
                "large change: " + files + " files and " + lines + " content lines (limits " + maxFiles + " files, "
                        + maxLines + " lines)"));
    }
}
