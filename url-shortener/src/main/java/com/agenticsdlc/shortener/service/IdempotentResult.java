package com.agenticsdlc.shortener.service;

/**
 * Outcome of {@link IdempotencyService#execute}.
 *
 * @param body     the response body (fresh or replayed)
 * @param status   the response status recorded for the key
 * @param replayed {@code true} when the stored response was returned instead of executing the action
 * @param <T>      body type
 */
public record IdempotentResult<T>(T body, int status, boolean replayed) {
}
