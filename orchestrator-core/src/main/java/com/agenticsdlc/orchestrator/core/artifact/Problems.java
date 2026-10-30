package com.agenticsdlc.orchestrator.core.artifact;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

/**
 * Collects validation problems for a payload and throws {@link InvalidArtifactException} when any exist.
 */
final class Problems {

    private final String payloadName;
    private final List<String> problems = new ArrayList<>();

    Problems(String payloadName) {
        this.payloadName = payloadName;
    }

    Problems require(boolean condition, String message) {
        if (!condition) {
            problems.add(message);
        }
        return this;
    }

    Problems requireText(@Nullable String value, String field) {
        return require(value != null && !value.isBlank(), field + " must not be blank");
    }

    Problems requireNonNull(@Nullable Object value, String field) {
        return require(value != null, field + " is required");
    }

    Problems requireNonEmpty(@Nullable Collection<?> values, String field) {
        return require(values != null && !values.isEmpty(), field + " must not be empty");
    }

    <T> Problems requireUnique(Collection<T> items, Function<T, String> key, String field) {
        Set<String> seen = new HashSet<>();
        for (T item : items) {
            String k = key.apply(item);
            if (!seen.add(k)) {
                problems.add(field + " contains duplicate id " + k);
            }
        }
        return this;
    }

    Problems requirePattern(@Nullable String value, String regex, String field) {
        return require(value != null && value.matches(regex), field + " '" + value + "' must match " + regex);
    }

    void throwIfAny() {
        if (!problems.isEmpty()) {
            throw new InvalidArtifactException(payloadName, problems);
        }
    }
}
