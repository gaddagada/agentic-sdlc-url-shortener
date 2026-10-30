package com.agenticsdlc.shortener.service.exception;

/**
 * The requested custom alias is syntactically invalid or reserved. Mapped to {@code 422 Unprocessable Content}.
 */
public final class AliasRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String alias;

    /**
     * Creates the exception.
     *
     * @param alias  the rejected alias
     * @param reason human readable reason
     */
    public AliasRejectedException(String alias, String reason) {
        super("The alias '" + alias + "' cannot be used: " + reason + ".");
        this.alias = alias;
    }

    public String getAlias() {
        return alias;
    }
}
