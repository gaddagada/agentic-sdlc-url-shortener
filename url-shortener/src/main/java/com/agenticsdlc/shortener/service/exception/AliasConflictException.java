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
        this(alias, "The alias '" + alias + "' is already in use.");
    }

    private AliasConflictException(String alias, String message) {
        super(message);
        this.alias = alias;
    }

    /**
     * Creates the exception for a bulk item whose alias is already requested by an earlier item of the same batch.
     *
     * @param alias      the duplicated alias
     * @param firstIndex index of the earlier item requesting it
     * @return the exception
     */
    public static AliasConflictException duplicateInBatch(String alias, int firstIndex) {
        return new AliasConflictException(alias,
                "The alias '" + alias + "' is already requested by item " + firstIndex + " of this batch.");
    }

    public String getAlias() {
        return alias;
    }
}
