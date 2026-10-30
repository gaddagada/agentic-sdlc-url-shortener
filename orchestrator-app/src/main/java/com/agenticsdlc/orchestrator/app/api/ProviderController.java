package com.agenticsdlc.orchestrator.app.api;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.agenticsdlc.orchestrator.app.api.dto.ProviderCircuitView;
import com.agenticsdlc.orchestrator.app.api.dto.ProviderView;
import com.agenticsdlc.orchestrator.app.llm.ProviderCatalog;
import com.agenticsdlc.orchestrator.app.llm.ProviderStatus;
import com.agenticsdlc.orchestrator.core.engine.OrchestrationEngine;
import com.agenticsdlc.orchestrator.core.engine.ProviderCircuit;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Agent providers, their availability (a disabled or unconfigured provider is listed as unavailable with the
 * reason) and, for LLM providers registered with the engine, their circuit-breaker state.
 */
@RestController
@RequestMapping("/api/v1/providers")
@Tag(name = "Providers", description = "Agent providers")
public class ProviderController {

    private final ProviderCatalog providers;
    private final OrchestrationEngine engine;

    /**
     * Creates the controller.
     *
     * @param providers the provider catalog
     * @param engine the engine (circuit-breaker state)
     */
    public ProviderController(ProviderCatalog providers, OrchestrationEngine engine) {
        this.providers = providers;
        this.engine = engine;
    }

    /**
     * Lists the providers.
     *
     * @return providers, deterministic first
     */
    @GetMapping
    @Operation(summary = "List agent providers, their availability and circuit-breaker state",
            operationId = "listProviders",
            description = "circuit is the engine-wide circuit breaker of an LLM provider (null for the deterministic "
                    + "agents and for providers disabled by configuration). While it is OPEN, new attempts run the "
                    + "deterministic agents instead of the provider until retryAt; then it is HALF_OPEN and the next "
                    + "attempt tries the provider again. lastFailure has credential-like tokens redacted and is at "
                    + "most 200 characters.")
    public List<ProviderView> list() {
        Map<String, ProviderCircuit> circuits = engine.providerCircuits();
        return providers.providers().stream().map(status -> view(status, circuits)).toList();
    }

    private static ProviderView view(ProviderStatus status, Map<String, ProviderCircuit> circuits) {
        ProviderCircuit circuit = circuits.get(status.id());
        return ProviderView.from(status, circuit == null ? null : ProviderCircuitView.from(circuit));
    }
}
