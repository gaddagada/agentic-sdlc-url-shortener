package com.agenticsdlc.shortener.service;

/**
 * Translates the failure of one bulk item into its {@link ItemError}.
 *
 * <p>Implemented by the API layer with the same mapper its exception handler uses, so a bulk item fails with exactly
 * the problem type and status the single-link endpoint answers for the same failure, while the service stays free of
 * HTTP concerns. The translation happens inside the idempotent execution, so replays return the stored errors.
 */
@FunctionalInterface
public interface ItemErrorMapper {

    /**
     * Translates a failure.
     *
     * @param failure exception thrown while creating one item
     * @return the item error; unexpected failures map to a generic error without internal details
     */
    ItemError toItemError(RuntimeException failure);
}
