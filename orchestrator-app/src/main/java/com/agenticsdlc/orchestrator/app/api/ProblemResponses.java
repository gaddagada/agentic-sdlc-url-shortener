package com.agenticsdlc.orchestrator.app.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Documents the RFC 9457 Problem Detail error responses ({@code application/problem+json}) an operation can
 * answer with. Read by the OpenAPI configuration; it has no effect on request handling.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ProblemResponses {

    /**
     * Returns the HTTP status codes of the documented error responses.
     *
     * @return status codes, for example {@code {400, 404, 409}}
     */
    int[] value();
}
