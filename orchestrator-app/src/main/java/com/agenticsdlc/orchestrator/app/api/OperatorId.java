package com.agenticsdlc.orchestrator.app.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller parameter of type {@code Actor} that is resolved from the {@code X-Operator-Id} header by
 * {@link OperatorIdentityResolver}. The request is rejected with 400 when the header is missing or malformed.
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface OperatorId {
}
