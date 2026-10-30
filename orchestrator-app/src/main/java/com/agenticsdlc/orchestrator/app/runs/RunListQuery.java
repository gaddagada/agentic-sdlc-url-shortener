package com.agenticsdlc.orchestrator.app.runs;

import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * A page request of the runs list with optional exact-match filters (combined with AND).
 *
 * @param page zero-based page number
 * @param size page size (1 to {@value #MAX_SIZE})
 * @param statuses run status names to include (any of them), empty for every status
 * @param scenario scenario key to match, or null
 * @param startedBy operator id to match, or null
 * @param sort the order
 */
public record RunListQuery(int page, int size, List<String> statuses, @Nullable String scenario,
                           @Nullable String startedBy, RunListSort sort) {

    /** Default page size. */
    public static final int DEFAULT_SIZE = 50;

    /** Maximum page size. */
    public static final int MAX_SIZE = 200;

    /**
     * Validates the paging, copies the statuses and turns blank filters into null.
     *
     * @throws IllegalArgumentException for a negative page or a size outside 1..{@value #MAX_SIZE}
     */
    public RunListQuery {
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("size must be within 1.." + MAX_SIZE);
        }
        statuses = List.copyOf(statuses);
        scenario = blankToNull(scenario);
        startedBy = blankToNull(startedBy);
        Objects.requireNonNull(sort, "sort");
    }

    /**
     * Returns the first page with the default size and order and no filter.
     *
     * @return the query
     */
    public static RunListQuery firstPage() {
        return new RunListQuery(0, DEFAULT_SIZE, List.of(), null, null, RunListSort.DEFAULT);
    }

    /**
     * Returns the offset of the first row of the page.
     *
     * @return {@code page * size}
     */
    public long offset() {
        return (long) page * size;
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
