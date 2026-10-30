package com.agenticsdlc.orchestrator.app.runs;

import java.util.List;

/**
 * One page of the runs list.
 *
 * @param items the rows of the page
 * @param page zero-based page number
 * @param size requested page size
 * @param total rows matching the filters, over all pages
 */
public record RunListPage(List<RunListItem> items, int page, int size, long total) {

    /**
     * Copies the items.
     */
    public RunListPage {
        items = List.copyOf(items);
    }

    /**
     * Returns the number of pages.
     *
     * @return {@code ceil(total / size)} (0 when nothing matches)
     */
    public int totalPages() {
        return size <= 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, (total + size - 1) / size);
    }
}
