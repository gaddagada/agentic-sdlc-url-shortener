package com.agenticsdlc.shortener.service;

import java.net.URI;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * Why one item of a bulk creation failed, in the shape of an RFC 9457 Problem Detail. Produced by an
 * {@link ItemErrorMapper} from the failure of that item, so that it carries exactly the problem {@code type} and
 * status the single-link endpoint would have answered.
 *
 * @param type   problem type URI
 * @param title  short summary of the problem type
 * @param status HTTP status the single-link endpoint would have answered
 * @param detail client-safe explanation of this occurrence
 */
public record ItemError(URI type, String title, int status, @Nullable String detail) {

    /** Validates the components. */
    public ItemError {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(title, "title");
    }
}
