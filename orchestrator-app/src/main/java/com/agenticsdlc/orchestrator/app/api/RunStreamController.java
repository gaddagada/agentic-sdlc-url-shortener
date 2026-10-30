package com.agenticsdlc.orchestrator.app.api;

import jakarta.servlet.http.HttpServletResponse;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.agenticsdlc.orchestrator.app.config.OrchestratorProperties;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Server-sent event streams ({@code text/event-stream}) of the operator console, served by {@link RunStreamHub}.
 *
 * <p>The streams reflect the run changes committed by <em>this</em> node. In a multi-node deployment a run that
 * another node is executing changes without events here: every stream response names this node in the
 * {@value #NODE_HEADER} header, and a client polls the runs whose {@code nodeId} names another node (the console also
 * keeps a slow safety poll while a stream is open).</p>
 */
@RestController
@RequestMapping("/api/v1/runs")
@Tag(name = "Run events", description = "Server-sent event streams of runs and of the runs list")
public class RunStreamController {

    /** Response header naming the engine node that serves the stream. */
    public static final String NODE_HEADER = "X-Sdlc-Node-Id";

    private static final String STREAM_FORMAT = "Server-sent events (text/event-stream). Every event's data is one "
            + "JSON document.";

    private final RunStreamHub hub;
    private final String nodeId;

    /**
     * Creates the controller.
     *
     * @param hub the stream hub
     * @param properties configuration (this node's id)
     */
    public RunStreamController(RunStreamHub hub, OrchestratorProperties properties) {
        this.hub = hub;
        this.nodeId = properties.engine().resolvedNodeId();
    }

    /**
     * Streams the changes of one run.
     *
     * @param id run id
     * @param lastEventId the last audit sequence number the client saw, or null for the whole trail
     * @param response the response (node header, no buffering by proxies)
     * @return the emitter
     */
    @GetMapping(path = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Stream the changes of a run (server-sent events)", operationId = "streamRunEvents",
            description = STREAM_FORMAT + " On connect: the audit events after Last-Event-ID (all without it) as "
                    + "'audit' events (SSE id = audit seq, data = AuditEventView), then the current 'run' (RunView). "
                    + "Then, for every change this node commits: the new 'audit' events, then the new 'run'. "
                    + "'heartbeat' every sdlc.console.sse-heartbeat-interval (15 s), data {\"time\": ISO-8601}. When "
                    + "the run is COMPLETED or ABORTED the stream ends after its final 'run' event. Reconnect with "
                    + "Last-Event-ID to resume without gaps. Only commits of this node are streamed (header "
                    + NODE_HEADER + "); poll runs held by other nodes. 503 (stream-limit-reached, Retry-After) beyond "
                    + "sdlc.console.max-sse-connections.",
            responses = @ApiResponse(responseCode = "200", description = "The event stream",
                    content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
                            schema = @Schema(type = "string", description = "text/event-stream"))))
    @ProblemResponses({404, 503})
    public SseEmitter runEvents(@PathVariable String id,
                                @Parameter(description = "Last audit seq the client received (resume point)")
                                @RequestHeader(value = "Last-Event-ID", required = false) @Nullable String lastEventId,
                                HttpServletResponse response) {
        SseEmitter emitter = hub.openRun(id, lastEventId);
        streamHeaders(response);
        return emitter;
    }

    /**
     * Streams a summary of every run change.
     *
     * @param response the response (node header, no buffering by proxies)
     * @return the emitter
     */
    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Stream run list changes (server-sent events)", operationId = "streamRunListEvents",
            description = STREAM_FORMAT + " A 'heartbeat' first and then every sdlc.console.sse-heartbeat-interval "
                    + "(15 s); a 'run-summary' (RunSummaryView, the item shape of GET /api/v1/runs) for every run "
                    + "change this node commits. No replay: load the list with GET /api/v1/runs and apply the "
                    + "summaries to it. Only commits of this node are streamed (header " + NODE_HEADER + "). 503 "
                    + "(stream-limit-reached, Retry-After) beyond sdlc.console.max-sse-connections.",
            responses = @ApiResponse(responseCode = "200", description = "The event stream",
                    content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
                            schema = @Schema(type = "string", description = "text/event-stream"))))
    @ProblemResponses(503)
    public SseEmitter runListEvents(HttpServletResponse response) {
        SseEmitter emitter = hub.openList();
        streamHeaders(response);
        return emitter;
    }

    private void streamHeaders(HttpServletResponse response) {
        response.setHeader(NODE_HEADER, nodeId);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache");
        // nginx and similar proxies must pass events through instead of buffering the response
        response.setHeader("X-Accel-Buffering", "no");
    }
}
