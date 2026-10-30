package com.agenticsdlc.orchestrator.core.policy.rules;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.agenticsdlc.orchestrator.core.artifact.Severity;
import com.agenticsdlc.orchestrator.core.json.JsonCodec;
import com.agenticsdlc.orchestrator.core.policy.GatePhase;
import com.agenticsdlc.orchestrator.core.policy.PolicyContext;
import com.agenticsdlc.orchestrator.core.policy.PolicyFinding;
import com.agenticsdlc.orchestrator.core.policy.PolicyRule;
import com.agenticsdlc.orchestrator.core.policy.Verdict;
import com.agenticsdlc.orchestrator.core.util.SecretPatterns;

import tools.jackson.databind.JsonNode;

/**
 * {@code SEC-002 SecretLeak} (EXIT of any stage): denies an artifact that contains something that looks like a
 * secret. The finding names the kind of secret, never the value.
 *
 * <p>The payload is converted to a JSON tree and every string value, every object key and every
 * {@code key: value} pair of a string property is scanned as raw text with {@link SecretPatterns}. Scanning the
 * values (rather than the serialized document) matters: JSON escaping would turn
 * {@code String password = "S3cretPass!";} into {@code password = \"S3cretPass!\"}, which the patterns miss.</p>
 */
public final class SecretLeakRule implements PolicyRule {

    /** Rule id. */
    public static final String ID = "SEC-002";

    private final JsonCodec codec;

    /**
     * Creates the rule with the standard codec.
     */
    public SecretLeakRule() {
        this(JsonCodec.standard());
    }

    /**
     * Creates the rule.
     *
     * @param codec codec used to convert payloads to JSON trees
     */
    public SecretLeakRule(JsonCodec codec) {
        this.codec = codec;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "SecretLeak";
    }

    @Override
    public Optional<PolicyFinding> evaluate(PolicyContext ctx) {
        if (ctx.phase() != GatePhase.EXIT || ctx.candidatePayload() == null) {
            return Optional.empty();
        }
        Set<String> kinds = scan(codec.toTree(ctx.candidatePayload()));
        if (kinds.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new PolicyFinding(ID, Severity.CRITICAL, Verdict.DENY,
                "artifact contains what looks like a secret: " + String.join(", ", kinds)));
    }

    /**
     * Returns the labels of the secret kinds found in the string values and keys of a JSON tree.
     *
     * @param root the tree
     * @return labels in discovery order, empty when clean
     */
    static Set<String> scan(JsonNode root) {
        Set<String> kinds = new LinkedHashSet<>();
        Deque<JsonNode> pending = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            JsonNode node = pending.pop();
            if (node.isString()) {
                kinds.addAll(SecretPatterns.findKinds(node.asString()));
            } else if (node.isObject()) {
                for (Map.Entry<String, JsonNode> property : node.properties()) {
                    kinds.addAll(SecretPatterns.findKinds(property.getKey()));
                    if (property.getValue().isString()) {
                        // structured credentials such as {"apiKey": "..."} only match as "key: value"
                        kinds.addAll(SecretPatterns.findKinds(property.getKey() + ": "
                                + property.getValue().asString()));
                    }
                    pending.push(property.getValue());
                }
            } else if (node.isArray()) {
                for (JsonNode element : node) {
                    pending.push(element);
                }
            }
        }
        return kinds;
    }
}
