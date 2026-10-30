package com.agenticsdlc.shortener.api.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;

import com.agenticsdlc.shortener.service.CreateLinkCommand;

/**
 * Request body of {@code POST /api/v1/links}.
 *
 * @param url         target URL (http/https, at most 2048 characters)
 * @param customAlias optional custom code
 * @param ttlSeconds  optional lifetime in seconds; mutually exclusive with {@code expiresAt}
 * @param expiresAt   optional absolute expiry; mutually exclusive with {@code ttlSeconds}
 */
@Schema(name = "CreateLinkRequest", description = "Short link to create")
public record CreateLinkRequest(
        @Schema(description = "Target URL, http or https", example = "https://example.com/some/long/path",
                maxLength = 2048, requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 2048) String url,

        @Schema(description = "Optional custom alias (case-sensitive)", example = "spring-sale",
                pattern = "^[A-Za-z0-9_-]{4,32}$")
        @Pattern(regexp = "^[A-Za-z0-9_-]{4,32}$", message = "must be 4-32 characters of letters, digits, '_' or '-'")
        @Nullable String customAlias,

        @Schema(description = "Lifetime in seconds; mutually exclusive with expiresAt", example = "86400",
                minimum = "1")
        @Positive @Nullable Long ttlSeconds,

        @Schema(description = "Absolute expiry (ISO-8601, future, at most shortener.max-ttl ahead); "
                + "mutually exclusive with ttlSeconds", example = "2030-01-01T00:00:00Z")
        @Nullable Instant expiresAt) {

    /** {@return whether at most one of ttlSeconds and expiresAt is set} */
    @JsonIgnore
    @AssertTrue(message = "ttlSeconds and expiresAt are mutually exclusive")
    public boolean isExpiryUnambiguous() {
        return ttlSeconds == null || expiresAt == null;
    }

    /** {@return the transport-independent command} */
    public CreateLinkCommand toCommand() {
        return new CreateLinkCommand(url, customAlias, ttlSeconds, expiresAt);
    }
}
