package com.agenticsdlc.orchestrator.core.workspace;

/**
 * An HTTP endpoint declared by a controller.
 *
 * @param method HTTP method ({@code GET}, {@code POST}, ...)
 * @param path full path (class-level prefix plus method-level path)
 * @param controller controller simple name
 * @param handler handler method name
 */
public record Endpoint(String method, String path, String controller, String handler) {
}
