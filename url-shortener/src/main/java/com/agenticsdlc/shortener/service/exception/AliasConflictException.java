package com.agenticsdlc.shortener.service.exception;

/**
 * The requested custom alias is already taken. Mapped to {@code 409 Conflict}.
 */
public final class AliasConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String alias;

    /**
     * Creates the exception.
     *
     * @param alias the conflicting alias
     */
    public AliasConflictException(String alias) {
        super("The alias '" + alias + "' is already in use.");
        this.alias = alias;
    }

    public String getAlias() {
        return alias;
    }
}
