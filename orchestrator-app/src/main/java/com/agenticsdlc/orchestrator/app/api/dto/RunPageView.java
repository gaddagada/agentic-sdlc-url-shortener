package com.agenticsdlc.orchestrator.app.api.dto;

import java.util.List;

/**
 * One page of the runs list ({@code GET /api/v1/runs}).
 *
 * @param items the runs of the page, in the requested order
 * @param page zero-based page number
 * @param size page size
 * @param total runs matching the filters, over all pages
 * @param totalPages number of pages ({@code ceil(total / size)})
 * @param sort the order applied, as {@code <field>,<asc|desc>}
 */
public record RunPageView(List<RunSummaryView> items, int page, int size, long total, int totalPages, String sort) {
}
