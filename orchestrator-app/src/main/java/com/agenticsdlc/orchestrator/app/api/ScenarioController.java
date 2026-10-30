package com.agenticsdlc.orchestrator.app.api;

import java.net.URI;
import java.util.List;

import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
     * @param provider optional provider override
     * @param operator operator from {@code X-Operator-Id}
     * @return 201 with the run
     */
    @PostMapping("/{key}/run")
    @Operation(summary = "Start a run of a scenario", operationId = "runScenario")
    public ResponseEntity<RunView> run(@PathVariable String key,
                                       @RequestParam(required = false) @Nullable @Size(max = 32) String provider,
                                       @OperatorId Actor operator) {
        Run run = launcher.launch(catalog.require(key), provider, operator);
        return ResponseEntity.created(URI.create("/api/v1/runs/" + run.id())).body(mapper.toView(run));
    }
}
