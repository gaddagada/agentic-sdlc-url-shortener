package com.agenticsdlc.orchestrator.app.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import org.springframework.core.io.ClassPathResource;

import com.agenticsdlc.orchestrator.core.policy.pack.PolicyPack;
import com.agenticsdlc.orchestrator.core.policy.pack.PolicyPackException;
import com.agenticsdlc.orchestrator.core.policy.pack.PolicyPackLoader;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Reads the policy pack named by {@code sdlc.policy.pack}: a JSON file, or a YAML file (name ending in {@code .yaml}
 * or {@code .yml}) that is parsed with Jackson's YAML mapper into a tree and re-serialized as JSON, so the core's
 * strict {@link PolicyPackLoader#fromJson(String)} validates both forms the same way (unknown fields, regexes, globs,
 * duplicate ids, protected built-in rules). Duplicate keys are rejected in both forms.
 *
 * <p>In YAML, quote values that must stay strings but look like numbers or booleans (for example
 * {@code version: "1.0"}); the loader reports an unquoted {@code 1.0} as a problem.</p>
 */
public final class PolicyPackFiles {

    /** Location prefix of a classpath resource. */
    public static final String CLASSPATH_PREFIX = "classpath:";

    private static final YAMLMapper YAML = YAMLMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).build();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private PolicyPackFiles() {
    }

    /**
     * Loads and validates a pack.
     *
     * @param location file system path or {@code classpath:<resource>}
     * @return the pack
     * @throws PolicyPackException listing every problem of the pack (also for YAML syntax errors)
     * @throws IllegalStateException when the location cannot be read
     */
    public static PolicyPack load(String location) {
        String text = read(location.strip());
        return parse(text, isYaml(location));
    }

    /**
     * Parses and validates a pack.
     *
     * @param text the pack
     * @param yaml whether the text is YAML (else JSON)
     * @return the pack
     * @throws PolicyPackException listing every problem
     */
    public static PolicyPack parse(String text, boolean yaml) {
        if (!yaml) {
            return PolicyPackLoader.fromJson(text);
        }
        JsonNode tree;
        try {
            tree = YAML.readTree(text);
        } catch (JacksonException e) {
            throw new PolicyPackException(List.of("$: not valid YAML: " + e.getOriginalMessage()));
        }
        if (tree == null || tree.isMissingNode() || tree.isNull()) {
            throw new PolicyPackException(List.of("$: the policy pack file is empty"));
        }
        return PolicyPackLoader.fromJson(JSON.writeValueAsString(tree));
    }

    /**
     * Returns whether a location names a YAML file.
     *
     * @param location the location
     * @return true for names ending in {@code .yaml} or {@code .yml} (case-insensitive)
     */
    public static boolean isYaml(String location) {
        String lower = location.strip().toLowerCase(Locale.ROOT);
        return lower.endsWith(".yaml") || lower.endsWith(".yml");
    }

    private static String read(String location) {
        try {
            if (location.startsWith(CLASSPATH_PREFIX)) {
                ClassPathResource resource = new ClassPathResource(location.substring(CLASSPATH_PREFIX.length()));
                if (!resource.exists()) {
                    throw new IllegalStateException("sdlc.policy.pack: classpath resource not found: " + location);
                }
                try (InputStream in = resource.getInputStream()) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            Path path = Path.of(location).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)) {
                throw new IllegalStateException("sdlc.policy.pack: file not found: " + path);
            }
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("sdlc.policy.pack: cannot read " + location + ": " + e.getMessage(), e);
        }
    }
}
