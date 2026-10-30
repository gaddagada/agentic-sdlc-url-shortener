package com.agenticsdlc.orchestrator.app.config;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Detects the repository root the orchestrator reasons over: the nearest directory, starting at a given directory
 * and walking up, that contains both a {@code pom.xml} and a {@code url-shortener} directory.
 */
public final class WorkspaceRootDetector {

    /** Directory that marks the repository root (the brownfield codebase). */
    static final String MARKER_DIRECTORY = "url-shortener";

    private WorkspaceRootDetector() {
    }

    /**
     * Detects the repository root.
     *
     * @param start directory to start from (usually the working directory)
     * @return the detected root, or {@code start} (normalized) when no ancestor qualifies
     */
    public static Path detect(Path start) {
        Path origin = start.toAbsolutePath().normalize();
        for (Path dir = origin; dir != null; dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve("pom.xml")) && Files.isDirectory(dir.resolve(MARKER_DIRECTORY))) {
                return dir;
            }
        }
        return origin;
    }
}
