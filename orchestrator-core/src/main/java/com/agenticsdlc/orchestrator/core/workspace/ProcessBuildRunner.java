package com.agenticsdlc.orchestrator.core.workspace;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link BuildRunner} based on {@link ProcessBuilder}. Only Maven commands are accepted ({@code ./mvnw},
 * {@code mvnw} or {@code mvn} followed by plain arguments; shell metacharacters are rejected). The wrapper
 * is resolved inside the sandbox ({@code mvnw.cmd} on Windows) and the process always runs with the sandbox
 * as working directory. Output is captured (last 50 lines) and surefire summary lines are parsed.
 *
 * <p>Callers pass the engine-configured command ({@code EngineConfig.buildCommand}, default
 * {@code ./mvnw -B -ntp -q verify}); the {@code buildCommand} an agent writes into a change set is informational
 * and never reaches this runner. The validation here is a second line of defence.</p>
 */
public final class ProcessBuildRunner implements BuildRunner {

    private static final Logger LOG = LoggerFactory.getLogger(ProcessBuildRunner.class);

    private static final Set<String> ALLOWED_EXECUTABLES = Set.of("./mvnw", "mvnw", "mvn");
    private static final Pattern FORBIDDEN = Pattern.compile("[;&|<>$`\\\\\"'\\n\\r]");
    private static final Pattern SUREFIRE = Pattern.compile("Tests run: (\\d+), Failures: (\\d+), Errors: (\\d+)");
    private static final int TAIL_LINES = 50;
    private static final int MAX_FAILURES = 20;

    private final boolean windows;

    /**
     * Creates a runner for the current operating system.
     */
    public ProcessBuildRunner() {
        this(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
    }

    /**
     * Creates a runner.
     *
     * @param windows whether to use Windows command resolution
     */
    ProcessBuildRunner(boolean windows) {
        this.windows = windows;
    }

    /** Environment variable names that must never reach a sandboxed build (provider keys, tokens, passwords). */
    static final Pattern SENSITIVE_ENV = Pattern.compile("(?i)(key|token|secret|password|passwd|credential|auth)");

    /**
     * Removes credential-like variables from a child process environment. Generated code and build plugins run
     * inside the sandbox; they must not be able to read the engine's API keys.
     *
     * @param environment the mutable environment of a {@link ProcessBuilder}
     */
    static void sanitizeEnvironment(Map<String, String> environment) {
        environment.keySet().removeIf(name -> SENSITIVE_ENV.matcher(name).find());
    }

    @Override
    public BuildResult run(Path workingDirectory, String command, Duration timeout) throws InterruptedException {
        Path dir = workingDirectory.toAbsolutePath().normalize();
        List<String> commandLine = commandLine(dir, command, windows);
        long start = System.nanoTime();
        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(commandLine).directory(dir.toFile()).redirectErrorStream(true);
            sanitizeEnvironment(builder.environment());
            process = builder.start();
        } catch (IOException e) {
            return BuildResult.notExecuted(command, "could not start build: " + e.getMessage());
        }
        Deque<String> tail = new ArrayDeque<>();
        List<String> allFailures = new ArrayList<>();
        List<String> summaries = new ArrayList<>();
        Thread reader = Thread.ofVirtual().name("sdlc-build-output").start(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(),
                    StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    synchronized (tail) {
                        tail.addLast(line);
                        if (tail.size() > TAIL_LINES) {
                            tail.removeFirst();
                        }
                        if (SUREFIRE.matcher(line).find()) {
                            summaries.add(line.strip());
                        }
                        if (isFailureLine(line) && allFailures.size() < MAX_FAILURES) {
                            allFailures.add(line.strip());
                        }
                    }
                }
            } catch (IOException e) {
                LOG.debug("build output stream closed error={}", e.getMessage());
            }
        });
        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            throw e;
        }
        if (!finished) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
        reader.join(Duration.ofSeconds(5));
        long durationMs = (System.nanoTime() - start) / 1_000_000L;
        synchronized (tail) {
            List<String> lines = new ArrayList<>(tail);
            if (!finished) {
                return new BuildResult(true, command, -1, durationMs, "build timed out after " + timeout, allFailures,
                        lines);
            }
            int exit = process.exitValue();
            String summary = summaries.isEmpty() ? (exit == 0 ? "build succeeded" : "build failed with exit code " + exit)
                    : summaries.getLast();
            return new BuildResult(true, command, exit, durationMs, summary, List.copyOf(allFailures), lines);
        }
    }

    /**
     * Validates a command and resolves it to a process command line.
     *
     * @param dir sandbox directory
     * @param command the command
     * @param windows whether to resolve for Windows
     * @return the command line
     * @throws IllegalArgumentException when the command or directory is not allowed
     */
    static List<String> commandLine(Path dir, String command, boolean windows) {
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("sandbox directory does not exist: " + dir);
        }
        String trimmed = command.strip();
        if (trimmed.isEmpty() || FORBIDDEN.matcher(trimmed).find()) {
            throw new IllegalArgumentException("build command contains forbidden characters");
        }
        List<String> parts = List.of(trimmed.split("\\s+"));
        String executable = parts.getFirst();
        if (!ALLOWED_EXECUTABLES.contains(executable)) {
            throw new IllegalArgumentException("only ./mvnw, mvnw or mvn commands are allowed, got: " + executable);
        }
        for (String argument : parts.subList(1, parts.size())) {
            if (argument.contains("..") || argument.startsWith("/") || argument.matches("^[A-Za-z]:.*")) {
                throw new IllegalArgumentException("build arguments must not reference paths outside the sandbox");
            }
        }
        List<String> line = new ArrayList<>();
        if ("mvn".equals(executable)) {
            if (windows) {
                line.addAll(List.of("cmd.exe", "/c", "mvn.cmd"));
            } else {
                line.add("mvn");
            }
        } else {
            Path wrapper = dir.resolve(windows ? "mvnw.cmd" : "mvnw").normalize();
            if (!wrapper.startsWith(dir) || !Files.isRegularFile(wrapper)) {
                throw new IllegalArgumentException("Maven wrapper not found in the sandbox: " + wrapper.getFileName());
            }
            if (windows) {
                line.addAll(List.of("cmd.exe", "/c", wrapper.toString()));
            } else {
                line.addAll(List.of("sh", wrapper.toString()));
            }
        }
        line.addAll(parts.subList(1, parts.size()));
        return line;
    }

    /**
     * Returns whether an output line reports a test failure or build error.
     *
     * @param line output line
     * @return true for failure lines
     */
    static boolean isFailureLine(String line) {
        return line.contains("<<< FAILURE!") || line.contains("<<< ERROR!") || line.startsWith("[ERROR]");
    }

    /**
     * Parses surefire totals from a summary line.
     *
     * @param line output line
     * @return {tests, failures, errors}, or an empty array when the line is not a summary
     */
    static int[] parseSurefire(String line) {
        Matcher m = SUREFIRE.matcher(line);
        if (!m.find()) {
            return new int[0];
        }
        return new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
    }
}
