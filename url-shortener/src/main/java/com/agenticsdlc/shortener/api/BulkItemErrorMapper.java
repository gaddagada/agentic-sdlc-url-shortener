package com.agenticsdlc.shortener.api;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

import com.agenticsdlc.shortener.service.ItemError;
import com.agenticsdlc.shortener.service.ItemErrorMapper;

/**
 * Translates the failure of a bulk item with the shared {@link ProblemMapper}, the mapper behind
 * {@link ApiExceptionHandler}: an item fails with exactly the problem type, title and status the single-link endpoint
 * would answer. Server-side failures (5xx) are logged like the exception handler logs them.
 */
@Component
class BulkItemErrorMapper implements ItemErrorMapper {

    private static final Logger log = LoggerFactory.getLogger(BulkItemErrorMapper.class);

    @Override
    public ItemError toItemError(RuntimeException failure) {
        ProblemDetail problem = ProblemMapper.toProblem(failure);
        if (problem.getStatus() >= 500) {
            log.error("bulk item failed status={} type={}", problem.getStatus(), failure.getClass().getName(),
                    failure);
        }
        return new ItemError(problem.getType(), Objects.requireNonNull(problem.getTitle()), problem.getStatus(),
                problem.getDetail());
    }
}
