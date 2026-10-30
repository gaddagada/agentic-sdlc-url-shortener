package com.agenticsdlc.orchestrator.app.runs;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

/**
 * The sort order of the runs list: one whitelisted field and a direction ({@code updatedAt,desc} by default). Ties
 * are broken by the run id, so pages are stable; rows without a value in the field ({@code endedAt} of a run that has
 * not ended, an unknown {@code startedBy}) come last in either direction, on every database.
 *
 * @param field the field
 * @param descending whether the order is descending
 */
public record RunListSort(Field field, boolean descending) {

    /** The default order: most recently updated first. */
    public static final RunListSort DEFAULT = new RunListSort(Field.UPDATED_AT, true);

    /**
     * Validates the field.
     */
    public RunListSort {
        Objects.requireNonNull(field, "field");
    }

    /**
     * The fields a list may be sorted by (each a column of the {@code runs} table).
     */
    public enum Field {
        /** Creation time. */
        CREATED_AT("createdAt"),
        /** Last update. */
        UPDATED_AT("updatedAt"),
        /** End time. */
        ENDED_AT("endedAt"),
        /** Status name. */
        STATUS("status"),
        /** Scenario key. */
        SCENARIO("scenario"),
        /** Operator who started the run. */
        STARTED_BY("startedBy");

        private final String apiName;

        Field(String apiName) {
            this.apiName = apiName;
        }

        /**
         * Returns the name of the field in the API ({@code sort=<name>,<direction>}), which is also the attribute
         * of the JPA entity.
         *
         * @return the API name
         */
        public String apiName() {
            return apiName;
        }
    }

    /**
     * Parses {@code <field>[,asc|desc]} (direction case-insensitive, default {@code asc}); null or blank yields
     * {@link #DEFAULT}.
     *
     * @param value the {@code sort} parameter
     * @return the sort
     * @throws IllegalArgumentException naming the allowed fields when the field or the direction is unknown
     */
    public static RunListSort parse(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT;
        }
        String[] parts = value.split(",", -1);
        if (parts.length > 2) {
            throw new IllegalArgumentException("sort must be <field>[,asc|desc], got '" + value.strip() + "'");
        }
        String name = parts[0].strip();
        Field field = Arrays.stream(Field.values()).filter(f -> f.apiName.equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("sort field '" + name + "' is not supported; use one "
                        + "of " + allowedFields()));
        boolean descending = false;
        if (parts.length == 2) {
            String direction = parts[1].strip().toLowerCase(Locale.ROOT);
            if (!direction.equals("asc") && !direction.equals("desc")) {
                throw new IllegalArgumentException("sort direction must be asc or desc, got '" + parts[1].strip()
                        + "'");
            }
            descending = direction.equals("desc");
        }
        return new RunListSort(field, descending);
    }

    /**
     * Returns the API names of the sortable fields.
     *
     * @return comma separated names
     */
    public static String allowedFields() {
        return Arrays.stream(Field.values()).map(Field::apiName).collect(Collectors.joining(", "));
    }

    /**
     * Renders the sort as {@code <field>,<direction>}.
     *
     * @return the parameter value
     */
    @Override
    public String toString() {
        return field.apiName + "," + (descending ? "desc" : "asc");
    }
}
