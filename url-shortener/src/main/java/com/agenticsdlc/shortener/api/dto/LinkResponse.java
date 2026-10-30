package com.agenticsdlc.shortener.api.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.shortener.domain.LinkStatus;
import com.agenticsdlc.shortener.service.LinkView;

/**
 * Link representation returned by the API.
 *
 * @param code        short code
 * @param shortUrl    absolute short URL
 * @param originalUrl target URL
 * @param status      effective status
 * @param createdAt   creation instant (UTC)
 * @param expiresAt   expiry instant (UTC), omitted when the link never expires
 * @param clickCount  total recorded clicks (eventually consistent, clicks are recorded asynchronously)
 */
@Schema(name = "LinkResponse", description = "A short link")
public record LinkResponse(
        @Schema(example = "aZ3kP9q") String code,
        @Schema(example = "http://localhost:8080/aZ3kP9q") String shortUrl,
        @Schema(example = "https://example.com/some/long/path") String originalUrl,
        LinkStatus status,
        Instant createdAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable Instant expiresAt,
        long clickCount) {

    /**
     * Maps the service read model.
     *
     * @param view link view
     * @return the response
     */
    public static LinkResponse from(LinkView view) {
        return new LinkResponse(view.code(), view.shortUrl(), view.originalUrl(), view.status(), view.createdAt(),
                view.expiresAt(), view.clickCount());
    }
}
