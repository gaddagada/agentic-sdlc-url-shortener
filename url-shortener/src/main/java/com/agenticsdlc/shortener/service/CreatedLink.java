package com.agenticsdlc.shortener.service;

/**
 * Result of {@link LinkService#create}.
 *
 * @param link     the created (or replayed) link
 * @param replayed {@code true} when the response was replayed for a repeated {@code Idempotency-Key}
 */
public record CreatedLink(LinkView link, boolean replayed) {
}
