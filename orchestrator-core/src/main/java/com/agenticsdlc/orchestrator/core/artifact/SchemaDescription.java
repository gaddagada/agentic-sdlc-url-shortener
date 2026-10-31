package com.agenticsdlc.orchestrator.core.artifact;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Describes a payload record component for LLM structured outputs: the JSON schema derived from the record
 * ({@code JsonSchemas.forRecord}) carries the text as the property's {@code description}, so the model reads the
 * rule next to the field it applies to.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface SchemaDescription {

    /**
     * Returns the description.
     *
     * @return the description shown to the model
     */
    String value();
}
