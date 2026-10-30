package com.agenticsdlc.shortener.api.dto;

import java.net.URI;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.shortener.service.BulkCreation;
import com.agenticsdlc.shortener.service.ItemError;

/**
 * Response body of {@code POST /api/v1/links/bulk}: one result per requested item, in request order.
 *
 * @param total   number of items in the request
 * @param created number of links created
 * @param failed  number of items that failed
 * @param results per-item results, {@code results[i].index == i}
 */
@Schema(name = "BulkCreateLinksResponse",
        description = "Outcome of a bulk creation; items succeed or fail independently")
public record BulkCreateLinksResponse(int total, int created, int failed, List<BulkItemResult> results) {

    /**
     * Maps the service outcome.
     *
     * @param creation per-item outcomes
     * @return the response
     */
    public static BulkCreateLinksResponse from(BulkCreation creation) {
        List<BulkItemResult> results = creation.items().stream().map(BulkItemResult::from).toList();
        return new BulkCreateLinksResponse(results.size(), creation.createdCount(), creation.failedCount(), results);
    }

    /** Outcome of one item. */
    public enum ItemStatus {
        /** The link was created; {@code link} is set. */
        CREATED,
        /** The item failed; {@code error} is set. */
        FAILED
    }

    /**
     * Result of one item: {@code link} when created, {@code error} when failed, the other one {@code null}.
     *
     * @param index  position of the item in the request (0-based)
     * @param status outcome
     * @param link   the created link, {@code null} when the item failed
     * @param error  why the item failed, {@code null} when it was created
     */
    @Schema(name = "BulkItemResult", description = "Outcome of one item of a bulk request")
    public record BulkItemResult(
            @Schema(example = "0") int index,
            ItemStatus status,
            @Schema(nullable = true) @Nullable LinkResponse link,
            @Schema(nullable = true) @Nullable BulkItemError error) {

        static BulkItemResult from(BulkCreation.Item item) {
            LinkResponse link = item.link() == null ? null : LinkResponse.from(item.link());
            BulkItemError error = item.error() == null ? null : BulkItemError.from(item.error());
            return new BulkItemResult(item.index(), item.succeeded() ? ItemStatus.CREATED : ItemStatus.FAILED, link,
                    error);
        }
    }

    /**
     * Why an item failed: the Problem Details fields the single-link endpoint would have answered for the same
     * failure (same {@code type} and {@code status}).
     *
     * @param type   problem type URI
     * @param title  short summary of the problem type
     * @param status HTTP status the single-link endpoint would have answered
     * @param detail explanation of this occurrence
     */
    @Schema(name = "BulkItemError", description = "Problem Details of a failed item")
    public record BulkItemError(
            @Schema(example = "https://agenticsdlc.dev/problems/alias-conflict") URI type,
            @Schema(example = "Alias already in use") String title,
            @Schema(example = "409") int status,
            @Schema(example = "The alias 'spring-sale' is already in use.") @Nullable String detail) {

        static BulkItemError from(ItemError error) {
            return new BulkItemError(error.type(), error.title(), error.status(), error.detail());
        }
    }
}
