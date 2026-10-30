package com.agenticsdlc.orchestrator.app.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.agenticsdlc.orchestrator.app.api.dto.ProviderView;
import com.agenticsdlc.orchestrator.app.llm.ProviderCatalog;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Agent providers and their availability (a disabled or unconfigured provider is listed as unavailable with the
 * reason).
 */
@RestController
@RequestMapping("/api/v1/providers")
@Tag(name = "Providers", description = "Agent providers")
public class ProviderController {

    private final ProviderCatalog providers;

    /**
     * Creates the controller.
     *
     * @param providers the provider catalog
     */
    public ProviderController(ProviderCatalog providers) {
        this.providers = providers;
    }

    /**
     * Lists the providers.
     *
     * @return providers, deterministic first
     */
    @GetMapping
    @Operation(summary = "List agent providers and their availability", operationId = "listProviders")
    public List<ProviderView> list() {
        return providers.providers().stream().map(ProviderView::from).toList();
    }
}
