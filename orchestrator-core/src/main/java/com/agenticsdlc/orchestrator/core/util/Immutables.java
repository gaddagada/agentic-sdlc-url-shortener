package com.agenticsdlc.orchestrator.core.util;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import org.jspecify.annotations.Nullable;

/**
 * Null-tolerant immutable copies used by record compact constructors (payload records may be produced by
 * LLMs or deserialized from older snapshots, where collections can be missing).
 */
public final class Immutables {

    private Immutables() {
    }

    /**
     * Returns an immutable copy of {@code list}, or an empty list when it is null.
     *
     * @param list the list or null
     * @param <T> element type
     * @return immutable copy
     */
    public static <T> List<T> list(@Nullable List<T> list) {
        return list == null ? List.of() : List.copyOf(list);
    }

    /**
     * Returns an immutable, insertion-ordered copy of {@code map}, or an empty map when it is null.
     *
     * @param map the map or null
     * @param <K> key type
     * @param <V> value type
     * @return immutable copy preserving iteration order
     */
    public static <K, V> Map<K, V> map(@Nullable Map<K, V> map) {
        if (map == null || map.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }

    /**
     * Returns an immutable sorted copy of {@code set}, or an empty set when it is null. Sorted sets keep
     * iteration order deterministic, which matters for canonical JSON and hashing.
     *
     * @param set the set or null
     * @return immutable sorted copy
     */
    public static SortedSet<String> sortedSet(@Nullable Set<String> set) {
        return Collections.unmodifiableSortedSet(set == null ? new TreeSet<>() : new TreeSet<>(set));
    }

    /**
     * Returns {@code text} or the empty string when it is null.
     *
     * @param text text or null
     * @return non-null text
     */
    public static String text(@Nullable String text) {
        return text == null ? "" : text;
    }
}
