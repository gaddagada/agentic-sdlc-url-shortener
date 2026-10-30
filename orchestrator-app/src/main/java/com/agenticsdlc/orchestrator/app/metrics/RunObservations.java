package com.agenticsdlc.orchestrator.app.metrics;

import java.io.Serial;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import com.agenticsdlc.orchestrator.app.events.RunCommitted;
import com.agenticsdlc.orchestrator.app.events.RunEventBus;
import com.agenticsdlc.orchestrator.core.audit.AuditEventType;
import com.agenticsdlc.orchestrator.core.model.HaltReason;
import com.agenticsdlc.orchestrator.core.model.RunSnapshot;
import com.agenticsdlc.orchestrator.core.model.RunStatus;
import com.agenticsdlc.orchestrator.core.model.StageStatus;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

/**
 * Wraps every run and every stage attempt in a Micrometer {@link Observation}, driven by the run changes this node
 * commits (the {@link RunEventBus}), because the engine's metrics SPI only reports completions.
 *
 * <ul>
 *   <li>{@value #RUN} starts when a run is created, or first seen RUNNING or WAITING_HUMAN (resumed, re-planned or
 *       recovered on this node), and stops when it leaves those states (COMPLETED, ABORTED or SAFE_STOPPED; a run
 *       denied at its start is observed with a zero duration). Low-cardinality key values:
 *       {@code scenario}, {@code autonomy} and, at the stop, {@code status}; high-cardinality: {@code run.id}. A
 *       safe-stop other than an operator stop is recorded as the observation's error (the halt reason).</li>
 *   <li>{@value #STAGE} (child of the run's observation) starts when a stage is seen RUNNING and stops when the stage
 *       leaves RUNNING or another attempt starts. Low-cardinality: {@code stage}, {@code provider} (the agent's
 *       provider: {@code deterministic}, {@code fallback}, {@code anthropic}, {@code ollama}), {@code attempt} and, at
 *       the stop, {@code status} (the stage status after the attempt); high-cardinality: {@code run.id}. A failed
 *       attempt (FAILED or RETRY_SCHEDULED) records the stage's last error.</li>
 * </ul>
 *
 * <p>With Spring Boot's observation handlers every observation is also a timer in the meter registry (long task timers
 * are off, {@code management.metrics.observations.ignored-meters=long-task-timer}: {@code sdlc.run.active} is the
 * recorder's timer of active run time) and, with tracing, a span: a run's trace holds one span per stage attempt. Run
 * ids are never tags of meters. Observations live in memory: a run that this node did not see start (another node, or
 * before a restart) is observed from the first change this node commits, and open observations are stopped with status
 * {@code NODE_SHUTDOWN} when the application stops. At most {@value #MAX_OPEN_RUNS} runs are observed at a time.</p>
 *
 * <p>Changes arrive in commit order on the bus's single dispatcher thread; the methods are synchronized only because
 * the shutdown runs on another thread.</p>
 */
@Component
public class RunObservations implements DisposableBean {

    /** Name of the run observation. */
    public static final String RUN = "sdlc.run";

    /** Name of the stage attempt observation. */
    public static final String STAGE = "sdlc.stage";

    /** Maximum number of runs observed at the same time. */
    static final int MAX_OPEN_RUNS = 10_000;

    /** Status key value of observations stopped by an application shutdown. */
    static final String NODE_SHUTDOWN = "NODE_SHUTDOWN";

    /** Status key value of a stage attempt whose end was not seen (another attempt started). */
    static final String SUPERSEDED = "SUPERSEDED";

    private static final Logger LOG = LoggerFactory.getLogger(RunObservations.class);

    private final ObservationRegistry registry;
    private final Map<String, OpenRun> open = new HashMap<>();
    private final RunEventBus.Subscription subscription;
    private final AtomicBoolean capacityWarned = new AtomicBoolean();

    /**
     * Creates the observer and subscribes it to the bus.
     *
     * @param bus the bus of committed run changes
     * @param registry the observation registry
     */
    public RunObservations(RunEventBus bus, ObservationRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.subscription = bus.subscribe(this::onCommitted);
    }

    /**
     * Applies one committed change: starts and stops the run and stage observations it implies.
     *
     * @param committed the change
     */
    synchronized void onCommitted(RunCommitted committed) {
        if (registry.isNoop()) {
            return;
        }
        RunSnapshot run = committed.snapshot();
        OpenRun observed = open.get(run.id());
        if (observed == null) {
            boolean created = committed.events().stream().anyMatch(e -> e.type() == AuditEventType.RUN_CREATED);
            if (!run.status().isActive() && !created) {
                return;
            }
            if (open.size() >= MAX_OPEN_RUNS) {
                if (capacityWarned.compareAndSet(false, true)) {
                    LOG.warn("not observing more runs: {} runs are observed already", MAX_OPEN_RUNS);
                }
                return;
            }
            observed = new OpenRun(Observation.createNotStarted(RUN, registry)
                    .lowCardinalityKeyValue("scenario", run.scenarioKey())
                    .lowCardinalityKeyValue("autonomy", run.options().autonomy().name())
                    .highCardinalityKeyValue("run.id", run.id())
                    .start());
            open.put(run.id(), observed);
        }
        observed.stages(run);
        if (!run.status().isActive()) {
            observed.stop(run.status().name(), haltError(run));
            open.remove(run.id());
        }
    }

    /**
     * Returns the number of runs observed right now (tests and diagnostics).
     *
     * @return open run observations
     */
    synchronized int openRuns() {
        return open.size();
    }

    /** Stops every open observation with status {@value #NODE_SHUTDOWN} and unsubscribes. */
    @Override
    public synchronized void destroy() {
        subscription.close();
        open.values().forEach(run -> run.stop(NODE_SHUTDOWN, null));
        open.clear();
    }

    private static @Nullable Throwable haltError(RunSnapshot run) {
        if (run.status() != RunStatus.SAFE_STOPPED || run.haltReason() == null
                || run.haltReason() == HaltReason.OPERATOR_STOP) {
            return null;
        }
        return new ObservedFailure("run safe-stopped: " + run.haltReason()
                + (run.haltDetail() == null ? "" : " (" + run.haltDetail() + ")"));
    }

    /**
     * Returns the provider key value of an agent id: {@code fallback} for the deterministic fallback, the part before
     * the first colon of a provider agent ({@code anthropic:<model>}), else the id itself.
     *
     * @param agentId the active agent of a stage, or null
     * @return the provider
     */
    static String provider(@Nullable String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return "unknown";
        }
        int colon = agentId.indexOf(':');
        if (colon < 0) {
            return agentId;
        }
        String prefix = agentId.substring(0, colon);
        return prefix.equals("fallback") ? "fallback" : prefix;
    }

    /** A run under observation with its open stage attempts. */
    private final class OpenRun {

        private final Observation observation;
        private final Map<String, OpenAttempt> attempts = new LinkedHashMap<>();

        OpenRun(Observation observation) {
            this.observation = observation;
        }

        void stages(RunSnapshot run) {
            for (RunSnapshot.StageSnapshot stage : run.stages()) {
                OpenAttempt current = attempts.get(stage.stageId());
                boolean running = stage.status() == StageStatus.RUNNING;
                if (current != null && (!running || !current.same(stage))) {
                    boolean sameAttempt = current.attempt == stage.attempts() && !running;
                    current.stop(sameAttempt ? stage.status().name() : SUPERSEDED,
                            sameAttempt ? failure(stage) : null);
                    attempts.remove(stage.stageId());
                    current = null;
                }
                if (running && current == null) {
                    attempts.put(stage.stageId(), new OpenAttempt(stage.attempts(), stage.startedAt(),
                            Observation.createNotStarted(STAGE, registry)
                                    .parentObservation(observation)
                                    .lowCardinalityKeyValue("stage", stage.stageId())
                                    .lowCardinalityKeyValue("provider", provider(stage.activeAgent()))
                                    .lowCardinalityKeyValue("attempt", String.valueOf(stage.attempts()))
                                    .highCardinalityKeyValue("run.id", run.id())
                                    .start()));
                }
            }
        }

        void stop(String status, @Nullable Throwable error) {
            attempts.values().forEach(attempt -> attempt.stop(status.equals(NODE_SHUTDOWN) ? NODE_SHUTDOWN
                    : SUPERSEDED, null));
            attempts.clear();
            observation.lowCardinalityKeyValue("status", status);
            if (error != null) {
                observation.error(error);
            }
            observation.stop();
        }

        private static @Nullable Throwable failure(RunSnapshot.StageSnapshot stage) {
            boolean failed = stage.status() == StageStatus.FAILED || stage.status() == StageStatus.RETRY_SCHEDULED;
            if (!failed) {
                return null;
            }
            return new ObservedFailure("stage " + stage.stageId() + " attempt " + stage.attempts() + " failed"
                    + (stage.lastError() == null ? "" : ": " + stage.lastError()));
        }
    }

    /** One stage attempt under observation. */
    private record OpenAttempt(int attempt, @Nullable Instant startedAt, Observation observation) {

        boolean same(RunSnapshot.StageSnapshot stage) {
            return attempt == stage.attempts() && Objects.equals(startedAt, stage.startedAt());
        }

        void stop(String status, @Nullable Throwable error) {
            observation.lowCardinalityKeyValue("status", status);
            if (error != null) {
                observation.error(error);
            }
            observation.stop();
        }
    }

    /**
     * The failure recorded on an observation: a halted run or a failed stage attempt (never thrown; no stack trace).
     */
    static final class ObservedFailure extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        ObservedFailure(String message) {
            super(message, null, false, false);
        }
    }
}
