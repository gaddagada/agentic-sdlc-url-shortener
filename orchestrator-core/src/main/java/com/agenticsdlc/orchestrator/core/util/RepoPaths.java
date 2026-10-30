package com.agenticsdlc.orchestrator.core.util;

/**
 * Normalization of repository-relative paths written by agents, so that path-based rules (protected paths,
 * allowed roots) cannot be bypassed with spellings such as {@code ./pom.xml}, {@code docs//x} or
 * {@code a\b}.
 */
public final class RepoPaths {

    private RepoPaths() {
    }

    /**
     * Normalizes a repository-relative path: backslashes become forward slashes, repeated slashes collapse,
     * {@code ./} segments are removed (also leading ones). A leading {@code /} and {@code ..} segments are kept,
     * so absolute and escaping paths stay recognizable.
     *
     * @param path the path
     * @return the normalized path
     */
    public static String normalize(String path) {
        String p = path.strip().replace('\\', '/');
        while (p.contains("//")) {
            p = p.replace("//", "/");
        }
        while (p.contains("/./")) {
            p = p.replace("/./", "/");
        }
        while (p.startsWith("./")) {
            p = p.substring(2);
        }
        if (p.endsWith("/.")) {
            p = p.substring(0, p.length() - 2);
        }
        return p;
    }

    /**
     * Returns whether a path has a {@code ..} segment.
     *
     * @param path the path (normalized or not)
     * @return true when the path could escape its root
     */
    public static boolean hasParentSegment(String path) {
        String p = normalize(path);
        return p.equals("..") || p.startsWith("../") || p.endsWith("/..") || p.contains("/../");
    }
}
