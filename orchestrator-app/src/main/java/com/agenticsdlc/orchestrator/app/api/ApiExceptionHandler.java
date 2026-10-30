package com.agenticsdlc.orchestrator.app.api;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.agenticsdlc.orchestrator.app.llm.ProviderUnavailableException;
import com.agenticsdlc.orchestrator.app.scenario.ScenarioNotFoundException;
import com.agenticsdlc.orchestrator.core.engine.InvalidCommandException;
import com.agenticsdlc.orchestrator.core.engine.InvalidRunStateException;
import com.agenticsdlc.orchestrator.core.engine.RunNotFoundException;

/**
 * Maps exceptions to RFC 9457 Problem Details ({@code application/problem+json}).
 *
 * <p>Validation failures and a missing or reserved operator identity are 400 (validation problems carry an
 * {@code errors} array of {@code {field, message}}); other {@link IllegalArgumentException}s are 400 with a generic
 * detail (only {@link InvalidRequestException} messages, written by the control plane, are returned); unknown runs,
 * artifacts and scenarios are 404; commands the engine refuses ({@link InvalidRunStateException} for the wrong
 * state, {@link InvalidCommandException} for invalid commands), four-eyes violations and unavailable providers are
 * 409 with the engine's message; a plain {@link IllegalStateException} (engine closed or shutting down) is 503;
 * everything else is a 500 without internal details. Extends {@link ResponseEntityExceptionHandler} so Spring
 * MVC's own exceptions (405, 406, 415, missing parameters, ...) are rendered consistently.</p>
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(OperatorIdentityException.class)
    ResponseEntity<ProblemDetail> handleOperatorIdentity(OperatorIdentityException ex) {
        return respond(problem(HttpStatus.BAD_REQUEST, ProblemTypes.OPERATOR_IDENTITY_REQUIRED,
                "Operator identity required", ex.getMessage()));
    }

    @ExceptionHandler(RunNotFoundException.class)
    ResponseEntity<ProblemDetail> handleRunNotFound(RunNotFoundException ex) {
        return respond(problem(HttpStatus.NOT_FOUND, ProblemTypes.RUN_NOT_FOUND, "Run not found", ex.getMessage()));
    }

    @ExceptionHandler(ArtifactNotFoundException.class)
    ResponseEntity<ProblemDetail> handleArtifactNotFound(ArtifactNotFoundException ex) {
        return respond(problem(HttpStatus.NOT_FOUND, ProblemTypes.ARTIFACT_NOT_FOUND, "Artifact not found",
                ex.getMessage()));
    }

    @ExceptionHandler(ScenarioNotFoundException.class)
    ResponseEntity<ProblemDetail> handleScenarioNotFound(ScenarioNotFoundException ex) {
        return respond(problem(HttpStatus.NOT_FOUND, ProblemTypes.SCENARIO_NOT_FOUND, "Scenario not found",
                ex.getMessage()));
    }

    @ExceptionHandler(InvalidRunStateException.class)
    ResponseEntity<ProblemDetail> handleInvalidRunState(InvalidRunStateException ex) {
        return respond(problem(HttpStatus.CONFLICT, ProblemTypes.INVALID_STATE, "Command not allowed in this state",
                ex.getMessage()));
    }

    /**
     * A plain {@link IllegalStateException} is what the engine throws once it is closed or shutting down (state
     * conflicts are {@link InvalidRunStateException}); the message is not returned.
     */
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ProblemDetail> handleIllegalState(IllegalStateException ex) {
        LOG.warn("request refused while the control plane is unavailable: {}", ex.getMessage());
        return respond(problem(HttpStatus.SERVICE_UNAVAILABLE, ProblemTypes.SERVICE_UNAVAILABLE,
                "Service unavailable", "control plane is shutting down"));
    }

    @ExceptionHandler(InvalidCommandException.class)
    ResponseEntity<ProblemDetail> handleInvalidCommand(InvalidCommandException ex) {
        return respond(problem(HttpStatus.CONFLICT, ProblemTypes.INVALID_COMMAND, "Command rejected",
                ex.getMessage()));
    }

    @ExceptionHandler(FourEyesViolationException.class)
    ResponseEntity<ProblemDetail> handleFourEyes(FourEyesViolationException ex) {
        return respond(problem(HttpStatus.CONFLICT, ProblemTypes.FOUR_EYES_VIOLATION, "Four-eyes rule violated",
                ex.getMessage()));
    }

    @ExceptionHandler(ProviderUnavailableException.class)
    ResponseEntity<ProblemDetail> handleProviderUnavailable(ProviderUnavailableException ex) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, ProblemTypes.PROVIDER_UNAVAILABLE,
                "Agent provider unavailable", ex.getMessage());
        problem.setProperty("provider", ex.providerId());
        return respond(problem);
    }

    @ExceptionHandler(InvalidRequestException.class)
    ResponseEntity<ProblemDetail> handleInvalidRequest(InvalidRequestException ex) {
        return respond(problem(HttpStatus.BAD_REQUEST, ProblemTypes.INVALID_ARGUMENT, "Invalid argument",
                ex.getMessage()));
    }

    /**
     * Other illegal arguments may carry library messages, so the detail is generic (the message is logged).
     */
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ProblemDetail> handleIllegalArgument(IllegalArgumentException ex) {
        LOG.info("invalid argument: {}", ex.getMessage());
        return respond(problem(HttpStatus.BAD_REQUEST, ProblemTypes.INVALID_ARGUMENT, "Invalid argument",
                "The request contains an invalid argument."));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        LOG.error("unhandled exception type={}", ex.getClass().getName(), ex);
        return respond(problem(HttpStatus.INTERNAL_SERVER_ERROR, ProblemTypes.INTERNAL_ERROR, "Internal error",
                "An unexpected error occurred."));
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
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, ProblemTypes.MALFORMED_REQUEST, "Malformed request",
                "The request body is missing, is not valid JSON or contains an unknown enum value.");
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    private ResponseEntity<Object> validationProblem(Exception ex, List<Violation> violations, HttpHeaders headers,
                                                     WebRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, ProblemTypes.VALIDATION_ERROR, "Validation failed",
                "The request is invalid; see 'errors' for details.");
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
        if (param != null && !(param.name().isEmpty() && param.value().isEmpty())) {
            return param.name().isEmpty() ? param.value() : param.name();
        }
        PathVariable path = parameter.getParameterAnnotation(PathVariable.class);
        if (path != null && !(path.name().isEmpty() && path.value().isEmpty())) {
            return path.name().isEmpty() ? path.value() : path.name();
        }
        String name = parameter.getParameterName();
        return name == null ? "arg" + parameter.getParameterIndex() : name;
    }

    private static ProblemDetail problem(HttpStatus status, URI type, String title, @Nullable String detail) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(type);
        problem.setTitle(title);
        problem.setDetail(detail);
        return problem;
    }

    private static ResponseEntity<ProblemDetail> respond(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    /**
     * One validation failure.
     *
     * @param field offending field, parameter or header name
     * @param message constraint message
     */
    public record Violation(String field, String message) {
    }
}
