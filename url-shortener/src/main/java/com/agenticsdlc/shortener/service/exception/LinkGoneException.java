package com.agenticsdlc.shortener.service.exception;

import java.util.Locale;

import com.agenticsdlc.shortener.domain.LinkStatus;

/**
 * The link exists but is expired or disabled. Mapped to {@code 410 Gone}.
 *
 * <p>Thrown on the redirect hot path, so the stack trace is not captured.
 */
public final class LinkGoneException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final LinkStatus status;

    /**
     * Creates the exception.
     *
     * @param code   the link code
     * @param status the effective status ({@code EXPIRED} or {@code DISABLED})
     */
    public LinkGoneException(String code, LinkStatus status) {
        super("Short link '" + code + "' is no longer available (" + status.name().toLowerCase(Locale.ROOT) + ").",
                null, false, false);
        this.code = code;
        this.status = status;
    }

    public String getCode() {
        return code;
    }

    public LinkStatus getStatus() {
        return status;
    }
}
