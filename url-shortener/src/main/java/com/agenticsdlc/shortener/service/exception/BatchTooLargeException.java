package com.agenticsdlc.shortener.service.exception;

/**
 * A bulk request has more items than a client's rate-limit bucket can ever hold, so waiting would not help: the
 * client must split the batch. Mapped to {@code 422 Unprocessable Content}.
 */
public final class BatchTooLargeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int items;
    private final int maxItems;

    /**
     * Creates the exception.
     *
     * @param items    items in the request
     * @param maxItems largest batch this server admits (the rate-limit capacity)
     */
    public BatchTooLargeException(int items, int maxItems) {
        super("The batch has " + items + " items but at most " + maxItems + " links can be created per client burst "
                + "(rate-limit capacity); split it into smaller batches.");
        this.items = items;
        this.maxItems = maxItems;
    }

    public int getItems() {
        return items;
    }

    public int getMaxItems() {
        return maxItems;
    }
}
