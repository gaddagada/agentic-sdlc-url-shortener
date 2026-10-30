package com.agenticsdlc.orchestrator.core.workspace;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Runs a build command inside a sandbox directory.
 */
public interface BuildRunner {

    /**
     * Runs the build.
     *
     * @param workingDirectory sandbox directory (the command never runs elsewhere)
     * @param command build command, for example {@code ./mvnw -pl url-shortener verify}
     * @param timeout maximum duration
     * @return the result (never throws for build failures)
     * @throws IllegalArgumentException when the command or directory is not allowed
     * @throws InterruptedException when interrupted while waiting
     */
    BuildResult run(Path workingDirectory, String command, Duration timeout) throws InterruptedException;
}
