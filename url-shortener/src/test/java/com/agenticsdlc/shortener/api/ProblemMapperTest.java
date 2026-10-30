package com.agenticsdlc.shortener.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.ProblemDetail;

import com.agenticsdlc.shortener.domain.LinkStatus;
import com.agenticsdlc.shortener.service.ItemError;
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

class ProblemMapperTest {

    static Stream<Arguments> failures() {
        return Stream.of(
                Arguments.of(new LinkNotFoundException("abcd"), 404, ProblemTypes.LINK_NOT_FOUND),
                Arguments.of(new LinkGoneException("abcd", LinkStatus.EXPIRED), 410, ProblemTypes.LINK_GONE),
                Arguments.of(new AliasConflictException("promo"), 409, ProblemTypes.ALIAS_CONFLICT),
                Arguments.of(AliasConflictException.duplicateInBatch("promo", 0), 409, ProblemTypes.ALIAS_CONFLICT),
                Arguments.of(new AliasRejectedException("admin", "it is reserved"), 422, ProblemTypes.ALIAS_REJECTED),
                Arguments.of(new UrlRejectedException("nope"), 422, ProblemTypes.URL_REJECTED),
                Arguments.of(new InvalidExpiryException("past"), 400, ProblemTypes.INVALID_EXPIRY),
                Arguments.of(new IdempotencyConflictException(), 422, ProblemTypes.IDEMPOTENCY_KEY_REUSED),
                Arguments.of(new IdempotencyInProgressException(), 409, ProblemTypes.IDEMPOTENCY_REQUEST_IN_PROGRESS),
                Arguments.of(new CodeGenerationException(4), 503, ProblemTypes.CODE_GENERATION_FAILED),
                Arguments.of(new RateLimitExceededException(3, 20, 1, 6), 429, ProblemTypes.RATE_LIMITED),
                Arguments.of(new BatchTooLargeException(30, 20), 422, ProblemTypes.BATCH_TOO_LARGE),
                Arguments.of(new IllegalStateException("jdbc password=hunter2"), 500, ProblemTypes.INTERNAL_ERROR));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("failures")
    void mapsEveryFailureToItsStatusAndType(RuntimeException failure, int status, URI type) {
        ProblemDetail problem = ProblemMapper.toProblem(failure);

        assertThat(problem.getStatus()).isEqualTo(status);
        assertThat(problem.getType()).isEqualTo(type);
        assertThat(problem.getTitle()).isNotBlank();
        assertThat(problem.getDetail()).isNotBlank().doesNotContain("hunter2");
        assertThat(ProblemMapper.isExpected(failure)).isEqualTo(status != 500);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failures")
    void bulkItemErrorsCarryExactlyTheSingleEndpointProblem(RuntimeException failure, int status, URI type) {
        ProblemDetail problem = ProblemMapper.toProblem(failure);

        ItemError error = new BulkItemErrorMapper().toItemError(failure);

        assertThat(error).isEqualTo(new ItemError(type, problem.getTitle(), status, problem.getDetail()));
    }

    @Test
    void detailsAreClientSafe() {
        assertThat(ProblemMapper.toProblem(new CodeGenerationException(4)).getDetail())
                .isEqualTo("Could not allocate a short code; please retry.");
        assertThat(ProblemMapper.toProblem(new UrlRejectedException("Only http.")).getDetail()).isEqualTo("Only http.");
        assertThat(ProblemMapper.toProblem(AliasConflictException.duplicateInBatch("promo", 2)).getDetail())
                .isEqualTo("The alias 'promo' is already requested by item 2 of this batch.");
    }

    @Test
    void addsTheExtraPropertiesOfLinkGoneAndBatchTooLarge() {
        assertThat(ProblemMapper.toProblem(new LinkGoneException("abcd", LinkStatus.DISABLED)).getProperties())
                .containsEntry("reason", "DISABLED");
        assertThat(ProblemMapper.toProblem(new BatchTooLargeException(30, 20)).getProperties())
                .containsEntry("maxItems", 20);
    }

    @Test
    void sharedFilterProblems() {
        ProblemDetail rateLimited = ProblemMapper.rateLimited("wait");
        ProblemDetail unauthorized = ProblemMapper.unauthorized();

        assertThat(rateLimited.getStatus()).isEqualTo(429);
        assertThat(rateLimited.getType()).isEqualTo(ProblemTypes.RATE_LIMITED);
        assertThat(rateLimited.getDetail()).isEqualTo("wait");
        assertThat(unauthorized.getStatus()).isEqualTo(401);
        assertThat(unauthorized.getType()).isEqualTo(ProblemTypes.UNAUTHORIZED);
        assertThat(unauthorized.getDetail()).contains("X-Api-Key");
    }
}
