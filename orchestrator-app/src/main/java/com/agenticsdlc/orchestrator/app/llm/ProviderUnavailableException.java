package com.agenticsdlc.orchestrator.app.llm;

/**
 * Thrown when a run asks for an agent provider that is unknown, disabled or currently unavailable.
 */
public final class ProviderUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String providerId;

    /**
     * Creates the exception.
     *
     * @param providerId the provider id
     * @param reason why it cannot be used (no secrets)
     */
    public ProviderUnavailableException(String providerId, String reason) {
        super("provider '" + providerId + "' is not available: " + reason);
        this.providerId = providerId;
    }

    /**
     * Returns the provider id.
     *
     * @return the provider id
     */
    public String providerId() {
        return providerId;
    }
}
