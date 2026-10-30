package com.agenticsdlc.shortener.api;

/**
 * Names shared by the OpenAPI annotations of the controllers and the document metadata in
 * {@code config.OpenApiConfig}.
 */
public final class OpenApiNames {

    /** Security scheme of the optional management API key ({@code X-Api-Key} header). */
    public static final String API_KEY_SCHEME = "ApiKey";

    private OpenApiNames() {
    }
}
