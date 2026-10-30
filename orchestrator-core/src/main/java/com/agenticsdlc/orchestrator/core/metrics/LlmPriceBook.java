package com.agenticsdlc.orchestrator.core.metrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.orchestrator.core.agent.llm.LlmUsage;

/**
 * An immutable table of LLM prices by provider and model, used by the engine to estimate the cost of every LLM call
 * when it records the call's usage. The core ships no prices ({@link #EMPTY} is the engine default): prices change and
 * differ by contract, so they are operator configuration that the application maps from its properties.
 *
 * <p>Lookup of {@code (providerId, model)}, both compared case-insensitively:</p>
 * <ol>
 *   <li>an entry for exactly that model;</li>
 *   <li>otherwise the entry with the longest model <em>prefix</em> of the model name that ends at a name boundary
 *       (the next character of the model name is not a letter or digit), so an entry for {@code claude-opus-5}
 *       prices {@code claude-opus-5-5} and {@code claude-opus-5-20261001}, but an entry for {@code gpt-4} does not
 *       price {@code gpt-4o};</li>
 *   <li>otherwise the provider default ({@link Builder#providerDefault});</li>
 *   <li>otherwise the call is unpriced ({@link #costMicroUsd} returns empty).</li>
 * </ol>
 */
public final class LlmPriceBook {

    /** A price book without prices: every call is unpriced. */
    public static final LlmPriceBook EMPTY = new LlmPriceBook(Map.of(), Map.of());

    private final Map<String, Map<String, LlmPrice>> models;
    private final Map<String, LlmPrice> providerDefaults;

    private LlmPriceBook(Map<String, Map<String, LlmPrice>> models, Map<String, LlmPrice> providerDefaults) {
        Map<String, Map<String, LlmPrice>> copy = new TreeMap<>();
        models.forEach((provider, byModel) -> copy.put(provider, Collections.unmodifiableMap(new TreeMap<>(byModel))));
        this.models = Collections.unmodifiableMap(copy);
        this.providerDefaults = Collections.unmodifiableMap(new TreeMap<>(providerDefaults));
    }

    /**
     * One entry of the price book.
     *
     * @param providerId provider id (lower case)
     * @param model model name or model prefix (lower case), or null for the provider default
     * @param price the price
     */
    public record Entry(String providerId, @Nullable String model, LlmPrice price) {
        /**
         * Validates the entry.
         */
        public Entry {
            Objects.requireNonNull(providerId, "providerId");
            Objects.requireNonNull(price, "price");
        }
    }

    /**
     * Returns a builder.
     *
     * @return a new, empty builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns whether the book has no price at all.
     *
     * @return true for a book without entries
     */
    public boolean isEmpty() {
        return models.isEmpty() && providerDefaults.isEmpty();
    }

    /**
     * Returns every entry, sorted by provider and model (provider defaults first, with a null model).
     *
     * @return the entries
     */
    public List<Entry> entries() {
        List<Entry> entries = new ArrayList<>();
        TreeSet<String> providers = new TreeSet<>(models.keySet());
        providers.addAll(providerDefaults.keySet());
        for (String provider : providers) {
            LlmPrice fallback = providerDefaults.get(provider);
            if (fallback != null) {
                entries.add(new Entry(provider, null, fallback));
            }
            models.getOrDefault(provider, Map.of()).forEach((model, price) -> entries.add(new Entry(provider, model,
                    price)));
        }
        return List.copyOf(entries);
    }

    /**
     * Looks up the price of a model (exact model, then the longest model prefix at a name boundary, then the provider
     * default).
     *
     * @param providerId provider id
     * @param model model name, or null (only the provider default can match)
     * @return the price, or empty when the call cannot be priced
     */
    public Optional<LlmPrice> lookup(String providerId, @Nullable String model) {
        String provider = key(providerId);
        if (provider.isEmpty()) {
            return Optional.empty();
        }
        Map<String, LlmPrice> byModel = models.getOrDefault(provider, Map.of());
        String name = model == null ? "" : key(model);
        if (!name.isEmpty()) {
            LlmPrice exact = byModel.get(name);
            if (exact != null) {
                return Optional.of(exact);
            }
            String best = null;
            for (String prefix : byModel.keySet()) {
                if (name.length() > prefix.length() && name.startsWith(prefix)
                        && !Character.isLetterOrDigit(name.charAt(prefix.length()))
                        && (best == null || prefix.length() > best.length())) {
                    best = prefix;
                }
            }
            if (best != null) {
                return Optional.of(byModel.get(best));
            }
        }
        return Optional.ofNullable(providerDefaults.get(provider));
    }

    /**
     * Returns the estimated cost of a call in micro-dollars (millionths of a US dollar).
     *
     * @param providerId provider id
     * @param model model name, or null
     * @param usage token usage
     * @return the cost ({@link LlmPrice#costMicroUsd}), or empty when no price applies
     */
    public Optional<Long> costMicroUsd(String providerId, @Nullable String model, LlmUsage usage) {
        Objects.requireNonNull(usage, "usage");
        return lookup(providerId, model).map(price -> price.costMicroUsd(usage));
    }

    /**
     * Returns the estimated cost of a call in micro-dollars from its token counts.
     *
     * @param providerId provider id
     * @param model model name, or null
     * @param inputTokens input tokens, cache reads and writes included
     * @param outputTokens output tokens
     * @param cacheReadTokens input tokens served from the prompt cache
     * @param cacheCreationTokens input tokens written to the prompt cache
     * @return the cost, or empty when no price applies
     */
    public Optional<Long> costMicroUsd(String providerId, @Nullable String model, long inputTokens, long outputTokens,
                                       long cacheReadTokens, long cacheCreationTokens) {
        return costMicroUsd(providerId, model, new LlmUsage(inputTokens, outputTokens, cacheReadTokens,
                cacheCreationTokens));
    }

    private static String key(@Nullable String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof LlmPriceBook book && models.equals(book.models)
                && providerDefaults.equals(book.providerDefaults);
    }

    @Override
    public int hashCode() {
        return Objects.hash(models, providerDefaults);
    }

    @Override
    public String toString() {
        return "LlmPriceBook" + entries();
    }

    /**
     * Builder of {@link LlmPriceBook}. Provider ids and model names are stored in lower case; a later entry for the
     * same provider and model replaces an earlier one.
     */
    public static final class Builder {
        private final Map<String, Map<String, LlmPrice>> models = new TreeMap<>();
        private final Map<String, LlmPrice> providerDefaults = new TreeMap<>();

        private Builder() {
        }

        /**
         * Sets the price of a model, or of every model whose name starts with {@code modelOrPrefix} at a name
         * boundary.
         *
         * @param providerId provider id (not blank)
         * @param modelOrPrefix model name or prefix (not blank)
         * @param price the price
         * @return this builder
         */
        public Builder price(String providerId, String modelOrPrefix, LlmPrice price) {
            Objects.requireNonNull(price, "price");
            models.computeIfAbsent(requireKey(providerId, "providerId"), k -> new TreeMap<>())
                    .put(requireKey(modelOrPrefix, "model"), price);
            return this;
        }

        /**
         * Sets the price of every model of a provider that has no model entry.
         *
         * @param providerId provider id (not blank)
         * @param price the price
         * @return this builder
         */
        public Builder providerDefault(String providerId, LlmPrice price) {
            Objects.requireNonNull(price, "price");
            providerDefaults.put(requireKey(providerId, "providerId"), price);
            return this;
        }

        private static String requireKey(@Nullable String value, String name) {
            String key = key(value);
            if (key.isEmpty()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return key;
        }

        /**
         * Builds the immutable price book.
         *
         * @return the price book
         */
        public LlmPriceBook build() {
            return models.isEmpty() && providerDefaults.isEmpty() ? EMPTY : new LlmPriceBook(models, providerDefaults);
        }
    }
}
