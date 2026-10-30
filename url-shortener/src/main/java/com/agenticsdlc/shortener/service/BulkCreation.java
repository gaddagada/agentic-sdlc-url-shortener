package com.agenticsdlc.shortener.service;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Outcome of a bulk creation: one {@link Item} per requested link, in request order. Also the JSON body stored for
 * idempotent replays, so it must round-trip through JSON.
 *
 * @param items per-item outcomes, {@code items.get(i).index() == i}
 */
public record BulkCreation(List<Item> items) {

    /** Copies the list. */
    public BulkCreation {
        items = List.copyOf(items);
    }

    /** {@return the number of links created} */
    public int createdCount() {
        return (int) items.stream().filter(Item::succeeded).count();
    }

    /** {@return the number of items that failed} */
    public int failedCount() {
        return items.size() - createdCount();
    }

    /**
     * Outcome of one item: exactly one of {@code link} and {@code error} is present.
     *
     * @param index position of the item in the request (0-based)
     * @param link  the created link, when the item succeeded
     * @param error why the item failed, otherwise
     */
    public record Item(int index, @Nullable LinkView link, @Nullable ItemError error) {

        /** Enforces that exactly one of link and error is present. */
        public Item {
            if ((link == null) == (error == null)) {
                throw new IllegalArgumentException("exactly one of link and error must be present");
            }
        }

        /**
         * A created item.
         *
         * @param index item position
         * @param link  created link
         * @return the outcome
         */
        public static Item created(int index, LinkView link) {
            return new Item(index, link, null);
        }

        /**
         * A failed item.
         *
         * @param index item position
         * @param error the failure
         * @return the outcome
         */
        public static Item failed(int index, ItemError error) {
            return new Item(index, null, error);
        }

        /** {@return whether the link was created} */
        public boolean succeeded() {
            return link != null;
        }
    }
}
