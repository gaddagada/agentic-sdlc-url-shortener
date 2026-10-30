package com.agenticsdlc.shortener.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

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
 * Maps exceptions to RFC 9457 Problem Details ({@code application/problem+json}).
 *
 * <p>The status, type, title and detail of service failures come from the shared {@link ProblemMapper} (also used
 * for the per-item errors of the bulk endpoint, so codes stay identical); this class adds the response headers
 * ({@code Retry-After}, rate-limit headers, {@code Cache-Control}) and logging.
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} so Spring MVC's own exceptions (405, 406, 415, missing
 * parameters, ...) are rendered consistently; validation failures carry an {@code errors} array of
 * {@code {field, message}}. Unexpected exceptions become a generic 500 without internal details. Errors of the
 * redirect path carry {@code Cache-Control: no-store} so that 404/410 are not cached heuristically.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    static final String RETRY_AFTER_SECONDS = "1";

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler({LinkNotFoundException.class, LinkGoneException.class})
    ResponseEntity<ProblemDetail> handleUnavailableLink(RuntimeException ex) {
        ProblemDetail problem = ProblemMapper.toProblem(ex);
        return ResponseEntity.status(problem.getStatus()).cacheControl(CacheControl.noStore()).body(problem);
    }

    @ExceptionHandler({AliasConflictException.class, AliasRejectedException.class, UrlRejectedException.class,
        InvalidExpiryException.class, IdempotencyConflictException.class, BatchTooLargeException.class})
    ResponseEntity<ProblemDetail> handleRejectedRequest(RuntimeException ex) {
        return respond(ProblemMapper.toProblem(ex));
    }

    @ExceptionHandler(IdempotencyInProgressException.class)
    ResponseEntity<ProblemDetail> handleIdempotencyInProgress(IdempotencyInProgressException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(ex.getRetryAfterSeconds()))
                .body(ProblemMapper.toProblem(ex));
    }

    @ExceptionHandler(CodeGenerationException.class)
    ResponseEntity<ProblemDetail> handleCodeGeneration(CodeGenerationException ex) {
        log.error("short code generation exhausted: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS).body(ProblemMapper.toProblem(ex));
    }

    @ExceptionHandler(RateLimitExceededException.class)
    ResponseEntity<ProblemDetail> handleRateLimitExceeded(RateLimitExceededException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(ex.getRetryAfterSeconds()))
                .header(ApiHeaders.RATE_LIMIT_LIMIT, Integer.toString(ex.getLimit()))
                .header(ApiHeaders.RATE_LIMIT_REMAINING, Long.toString(ex.getRemaining()))
                .body(ProblemMapper.toProblem(ex));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("unhandled exception type={}", ex.getClass().getName(), ex);
        return respond(ProblemMapper.internalError());
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        List<Violation> violations = new ArrayList<>();
        ex.getBindingResult().getAllErrors().forEach(error -> violations.add(violation(error, null)));
        return validationProblem(ex, violations, headers, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers,
                                                                            HttpStatusCode status,
                                                                            WebRequest request) {
        List<Violation> violations = new ArrayList<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            if (result instanceof ParameterErrors errors) {
                errors.getAllErrors().forEach(error -> violations.add(violation(error, null)));
            } else {
                String name = parameterName(result.getMethodParameter());
                result.getResolvableErrors().forEach(error -> violations.add(violation(error, name)));
            }
        }
        return validationProblem(ex, violations, headers, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        ProblemDetail problem = ProblemMapper.problem(HttpStatus.BAD_REQUEST, ProblemTypes.MALFORMED_REQUEST,
                "Malformed request", "The request body is missing or is not valid JSON for this operation.");
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    private ResponseEntity<Object> validationProblem(Exception ex, List<Violation> violations, HttpHeaders headers,
                                                     WebRequest request) {
        ProblemDetail problem = ProblemMapper.problem(HttpStatus.BAD_REQUEST, ProblemTypes.VALIDATION_ERROR,
                "Validation failed", "The request is invalid; see 'errors' for details.");
        violations.sort(Comparator.comparing(Violation::field).thenComparing(Violation::message));
        problem.setProperty("errors", List.copyOf(violations));
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    private static Violation violation(MessageSourceResolvable error, @Nullable String fallbackName) {
        String field;
        if (error instanceof FieldError fieldError) {
            field = fieldError.getField();
        } else if (fallbackName != null) {
            field = fallbackName;
        } else if (error instanceof ObjectError objectError) {
            field = objectError.getObjectName();
        } else {
            field = "request";
        }
        String message = error.getDefaultMessage();
        return new Violation(field, message == null ? "is invalid" : message);
    }

    private static String parameterName(MethodParameter parameter) {
        RequestHeader header = parameter.getParameterAnnotation(RequestHeader.class);
        if (header != null) {
            return header.name().isEmpty() ? header.value() : header.name();
        }
        RequestParam param = parameter.getParameterAnnotation(RequestParam.class);
        if (param != null) {
            return param.name().isEmpty() ? param.value() : param.name();
        }
        String name = parameter.getParameterName();
        return name == null ? "arg" + parameter.getParameterIndex() : name;
    }

    private static ResponseEntity<ProblemDetail> respond(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    /**
     * One validation failure.
     *
     * @param field   offending field, parameter or header name
     * @param message constraint message
     */
    public record Violation(String field, String message) {
    }
}
