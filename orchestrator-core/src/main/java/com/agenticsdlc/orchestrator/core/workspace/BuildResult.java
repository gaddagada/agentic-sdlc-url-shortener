package com.agenticsdlc.orchestrator.core.workspace;

import java.util.List;

import com.agenticsdlc.orchestrator.core.util.Immutables;

/**
 * Result of a sandbox build.
 *
 * @param executed whether the command ran
 * @param command the command
 * @param exitCode exit code ({@code -1} on timeout or when not executed)
 * @param durationMs duration
 * @param summary one-line summary (surefire totals when available)
 * @param failures failing tests or error lines (at most 20)
 * @param outputTail last 50 output lines
 */
public record BuildResult(boolean executed, String command, int exitCode, long durationMs, String summary,
                          List<String> failures, List<String> outputTail) {

    /**
     * Copies collections.
     */
    public BuildResult {
        summary = Immutables.text(summary);
        failures = Immutables.list(failures);
        outputTail = Immutables.list(outputTail);
    }

    /**
     * Returns a result for a build that was not executed.
     *
     * @param command the command
     * @param reason why it was not executed
     * @return the result
     */
    public static BuildResult notExecuted(String command, String reason) {
        return new BuildResult(false, command, -1, 0L, reason, List.of(), List.of());
    }
}
