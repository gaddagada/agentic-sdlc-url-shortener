package com.agenticsdlc.orchestrator.app.api;

import java.net.URI;

/**
 * RFC 9457 problem {@code type} URIs returned by the control plane API. Clients should branch on the {@code type},
 * not on the human-readable {@code title} or {@code detail}.
 */
public final class ProblemTypes {

    /** Common prefix of all problem types. */
    public static final String BASE = "https://agenticsdlc.dev/problems/";

    /** Bean validation failed; see the {@code errors} property. */
    public static final URI VALIDATION_ERROR = of("validation-error");
    /** The body is missing or not valid JSON. */
    public static final URI MALFORMED_REQUEST = of("malformed-request");
    /** {@code X-Operator-Id} missing or malformed. */
    public static final URI OPERATOR_IDENTITY_REQUIRED = of("operator-identity-required");
    /** {@code X-Api-Key} missing or wrong. */
    public static final URI UNAUTHORIZED = of("unauthorized");
    /** Unknown run. */
    public static final URI RUN_NOT_FOUND = of("run-not-found");
    /** Unknown artifact stage or version. */
    public static final URI ARTIFACT_NOT_FOUND = of("artifact-not-found");
    /** Unknown scenario. */
    public static final URI SCENARIO_NOT_FOUND = of("scenario-not-found");
    /** The command is not allowed in the current run or stage state. */
    public static final URI INVALID_STATE = of("invalid-state");
    /** The command is invalid for the run (budget exhausted, unknown stage, missing answers, ...). */
    public static final URI INVALID_COMMAND = of("invalid-command");
    /** The requested agent provider cannot serve the run. */
    public static final URI PROVIDER_UNAVAILABLE = of("provider-unavailable");
    /** Approving would break the four-eyes rule ({@code sdlc.governance.four-eyes}). */
    public static final URI FOUR_EYES_VIOLATION = of("four-eyes-violation");
    /** Invalid argument. */
    public static final URI INVALID_ARGUMENT = of("invalid-argument");
    /** The control plane is shutting down (engine closed); retry against a running instance. */
    public static final URI SERVICE_UNAVAILABLE = of("service-unavailable");
    /** Unexpected server error. */
    public static final URI INTERNAL_ERROR = of("internal-error");

    private ProblemTypes() {
    }

    private static URI of(String slug) {
        return URI.create(BASE + slug);
    }
}
