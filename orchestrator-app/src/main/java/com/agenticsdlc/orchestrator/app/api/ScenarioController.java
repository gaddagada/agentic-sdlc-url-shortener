package com.agenticsdlc.orchestrator.app.api;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.agenticsdlc.orchestrator.app.api.dto.RunScenarioRequest;
import com.agenticsdlc.orchestrator.app.api.dto.RunView;
import com.agenticsdlc.orchestrator.app.api.dto.ScenarioView;
import com.agenticsdlc.orchestrator.app.scenario.ScenarioCatalog;
import com.agenticsdlc.orchestrator.core.audit.Actor;
import com.agenticsdlc.orchestrator.core.model.Run;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * The scenario catalog and one-click scenario runs.
 */
@RestController
@RequestMapping("/api/v1/scenarios")
@Tag(name = "Scenarios", description = "Executable demo scenarios")
public class ScenarioController {

    private final ScenarioCatalog catalog;
    private final RunLauncher launcher;
    private final RunViewMapper mapper;

    /**
     * Creates the controller.
     *
     * @param catalog scenario catalog
     * @param launcher run launcher
     * @param mapper view mapper
     */
    public ScenarioController(ScenarioCatalog catalog, RunLauncher launcher, RunViewMapper mapper) {
        this.catalog = catalog;
        this.launcher = launcher;
        this.mapper = mapper;
    }

    /**
     * Lists the scenarios.
     *
     * @return scenarios in catalog order
     */
    @GetMapping
    @Operation(summary = "List the scenarios", operationId = "listScenarios")
    public List<ScenarioView> list() {
        return catalog.all().stream().map(ScenarioView::from).toList();
    }

    /**
     * Starts a run of a scenario.
     *
     * @param key scenario key
     * @param provider optional provider override (query parameter, kept for the console and scripts)
     * @param operator operator from {@code X-Operator-Id}
     * @param request optional body with a provider and budget overrides
     * @return 201 with the run
     */
    @PostMapping("/{key}/run")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Start a run of a scenario", operationId = "runScenario",
            description = "The optional body overrides the provider and the budget; the provider may also be given "
                    + "as the provider query parameter (a different provider in the body is refused with 400).")
    @ProblemResponses({400, 404, 409, 503})
    public ResponseEntity<RunView> run(@PathVariable String key,
                                       @RequestParam(required = false) @Nullable @Size(max = 32) String provider,
                                       @OperatorId Actor operator,
                                       @Valid @RequestBody(required = false) @Nullable RunScenarioRequest request) {
        String bodyProvider = request == null || request.provider() == null || request.provider().isBlank() ? null
                : request.provider().strip();
        String queryProvider = provider == null || provider.isBlank() ? null : provider.strip();
        if (bodyProvider != null && queryProvider != null && !bodyProvider.equals(queryProvider)) {
            throw new InvalidRequestException("provider given twice with different values (query parameter '"
                    + queryProvider + "', body '" + bodyProvider + "')");
        }
        Run run = launcher.launch(catalog.require(key), bodyProvider != null ? bodyProvider : queryProvider,
                request == null ? null : request.budget(), operator);
        return ResponseEntity.created(URI.create("/api/v1/runs/" + run.id())).body(mapper.toView(run));
    }
}
