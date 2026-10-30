package com.agenticsdlc.shortener.api;

import java.net.URI;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import com.agenticsdlc.shortener.service.exception.AliasConflictException;
import com.agenticsdlc.shortener.service.exception.AliasRejectedException;
import com.agenticsdlc.shortener.service.exception.BatchTooLargeException;
import com.agenticsdlc.shortener.service.exception.CodeGenerationException;
import com.agenticsdlc.shortener.service.exception.IdempotencyConflictException;
import com.agenticsdlc.shortener.service.exception.IdempotencyInProgressException;
import com.agenticsdlc.shortener.service.exception.InvalidExpiryException;
import com.agenticsdlc.shortener.service.exception.LinkGoneException;
import com.agenticsdlc.shortener.service.exception.LinkNotFoundException;
import com.agenticsdlc.shortener.service.exception.RateLimitExceededException;
import com.agenticsdlc.shortener.service.exception.UrlRejectedException;

/**
 * Single source of truth for turning failures into RFC 9457 Problem Details (status, {@code type}, {@code title},
 * {@code detail}).
 *
 * <p>Used by {@link ApiExceptionHandler} for whole-request errors, by the bulk endpoint for its per-item errors
 * ({@link BulkItemErrorMapper}) and by the servlet filters for {@code 429}/{@code 401}, so a failure has the same type
 * and status wherever it surfaces. Response headers ({@code Retry-After}, {@code Cache-Control}) are not part of a
 * Problem Detail and stay with the caller. Unknown failures map to a generic {@code 500 internal-error} whose detail
 * never reveals internals.
 */
public final class ProblemMapper {

    static final String INTERNAL_ERROR_DETAIL = "An unexpected error occurred.";
    static final String CODE_GENERATION_DETAIL = "Could not allocate a short code; please retry.";
    static final String UNAUTHORIZED_DETAIL = "A valid " + ApiHeaders.API_KEY + " header is required.";

    private ProblemMapper() {
    }

    /**
     * Maps a failure to its Problem Detail.
     *
     * @param failure the failure
     * @return the Problem Detail, {@code internal-error} for unknown failures
     */
    public static ProblemDetail toProblem(Throwable failure) {
        return switch (failure) {
            case LinkNotFoundException e ->
                    problem(HttpStatus.NOT_FOUND, ProblemTypes.LINK_NOT_FOUND, "Link not found", e.getMessage());
            case LinkGoneException e -> {
                ProblemDetail problem = problem(HttpStatus.GONE, ProblemTypes.LINK_GONE, "Link no longer available",
                        e.getMessage());
                problem.setProperty("reason", e.getStatus().name());
                yield problem;
            }
            case AliasConflictException e ->
                    problem(HttpStatus.CONFLICT, ProblemTypes.ALIAS_CONFLICT, "Alias already in use", e.getMessage());
            case AliasRejectedException e -> problem(HttpStatus.UNPROCESSABLE_CONTENT, ProblemTypes.ALIAS_REJECTED,
                    "Alias not allowed", e.getMessage());
            case UrlRejectedException e -> problem(HttpStatus.UNPROCESSABLE_CONTENT, ProblemTypes.URL_REJECTED,
                    "URL not allowed", e.getReason());
            case InvalidExpiryException e ->
                    problem(HttpStatus.BAD_REQUEST, ProblemTypes.INVALID_EXPIRY, "Invalid expiry", e.getMessage());
            case IdempotencyConflictException e -> problem(HttpStatus.UNPROCESSABLE_CONTENT,
                    ProblemTypes.IDEMPOTENCY_KEY_REUSED, "Idempotency key reused", e.getMessage());
            case IdempotencyInProgressException e -> problem(HttpStatus.CONFLICT,
                    ProblemTypes.IDEMPOTENCY_REQUEST_IN_PROGRESS, "Request in progress", e.getMessage());
            case CodeGenerationException e -> problem(HttpStatus.SERVICE_UNAVAILABLE,
                    ProblemTypes.CODE_GENERATION_FAILED, "Short code unavailable", CODE_GENERATION_DETAIL);
            case RateLimitExceededException e -> rateLimited(e.getMessage());
            case BatchTooLargeException e -> {
                ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_CONTENT, ProblemTypes.BATCH_TOO_LARGE,
                        "Batch too large", e.getMessage());
                problem.setProperty("maxItems", e.getMaxItems());
                yield problem;
            }
            default -> internalError();
        };
    }

    /**
     * Tells whether a failure is an expected business outcome, i.e. not mapped to {@code internal-error}.
     *
     * @param failure the failure
     * @return {@code false} for unexpected failures, which deserve an error log
     */
    public static boolean isExpected(Throwable failure) {
        return !ProblemTypes.INTERNAL_ERROR.equals(toProblem(failure).getType());
    }

    /**
     * {@code 429 rate-limited}, shared by the rate-limit filter and the bulk endpoint.
     *
     * @param detail client-safe explanation including the wait time
     * @return the Problem Detail
     */
    public static ProblemDetail rateLimited(String detail) {
        return problem(HttpStatus.TOO_MANY_REQUESTS, ProblemTypes.RATE_LIMITED, "Too many requests", detail);
    }

    /** {@return {@code 401 unauthorized}: missing or wrong API key; the same for both so nothing is revealed} */
    public static ProblemDetail unauthorized() {
        return problem(HttpStatus.UNAUTHORIZED, ProblemTypes.UNAUTHORIZED, "Unauthorized", UNAUTHORIZED_DETAIL);
    }

    /** {@return the generic {@code 500 internal-error}, without internal details} */
    public static ProblemDetail internalError() {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ProblemTypes.INTERNAL_ERROR, "Internal error",
                INTERNAL_ERROR_DETAIL);
    }

    static ProblemDetail problem(HttpStatus status, URI type, String title, @Nullable String detail) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(type);
        problem.setTitle(title);
        problem.setDetail(detail);
        return problem;
    }
}
