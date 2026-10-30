package com.agenticsdlc.shortener.api;

import java.time.Clock;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.agenticsdlc.shortener.api.RedirectMetrics.Outcome;
import com.agenticsdlc.shortener.service.ClickRecorded;
import com.agenticsdlc.shortener.service.ClientIpResolver;
import com.agenticsdlc.shortener.service.LinkService;
import com.agenticsdlc.shortener.service.RedirectTarget;
import com.agenticsdlc.shortener.service.exception.LinkGoneException;
import com.agenticsdlc.shortener.service.exception.LinkNotFoundException;

/**
 * Resolves short codes: {@code GET /{code}} answers {@code 302 Found}.
 *
 * <p>302 rather than 301 so that browsers and proxies do not cache the mapping permanently: expiry, deactivation and
 * click analytics stay effective. {@code Cache-Control: no-store} reinforces this. The click is recorded
 * asynchronously by publishing {@link ClickRecorded}; {@code HEAD} requests (link previews, monitors) are not counted.
 * The path pattern only admits valid code characters, so other single-segment paths never reach the database.
 */
@RestController
@Tag(name = "Redirect")
public class RedirectController {

    private final LinkService linkService;
    private final ApplicationEventPublisher events;
    private final ClientIpResolver clientIpResolver;
    private final RedirectMetrics metrics;
    private final Clock clock;

    /**
     * Creates the controller.
     *
     * @param linkService      resolves codes
     * @param events           publishes click events
     * @param clientIpResolver resolves the client IP for visitor hashing
     * @param metrics          redirect meters
     * @param clock            time source
     */
    public RedirectController(LinkService linkService, ApplicationEventPublisher events,
                              ClientIpResolver clientIpResolver, RedirectMetrics metrics, Clock clock) {
        this.linkService = linkService;
        this.events = events;
        this.clientIpResolver = clientIpResolver;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * Redirects to the target URL of an active link.
     *
     * @param code    short code
     * @param request current request (referrer, user agent, client IP)
     * @return {@code 302 Found} with {@code Location}
     */
    @Operation(operationId = "redirect", summary = "Follow a short link")
    @ApiResponse(responseCode = "302", description = "Redirect to the target URL", headers = {
        @Header(name = "Location", description = "Target URL", schema = @Schema(type = "string", format = "uri")),
        @Header(name = "Cache-Control", description = "Always no-store", schema = @Schema(type = "string"))})
    @ApiResponse(responseCode = "404", description = "Unknown code",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "410", description = "Link expired or disabled",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/{code:[A-Za-z0-9_-]{4,32}}")
    public ResponseEntity<Void> redirect(@PathVariable String code, HttpServletRequest request) {
        long started = System.nanoTime();
        @Nullable Outcome outcome = null;
        try {
            RedirectTarget target = linkService.resolveRedirect(code);
            if (!HttpMethod.HEAD.matches(request.getMethod())) {
                events.publishEvent(new ClickRecorded(
                        target.linkId(),
                        clock.instant(),
                        request.getHeader(HttpHeaders.REFERER),
                        request.getHeader(HttpHeaders.USER_AGENT),
                        clientIpResolver.resolve(request.getRemoteAddr(),
                                request.getHeader(ClientIpResolver.FORWARDED_FOR))));
            }
            outcome = Outcome.REDIRECTED;
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(target.location())
                    .cacheControl(CacheControl.noStore())
                    .build();
        } catch (LinkNotFoundException e) {
            outcome = Outcome.NOT_FOUND;
            throw e;
        } catch (LinkGoneException e) {
            outcome = Outcome.GONE;
            throw e;
        } finally {
            if (outcome != null) {
                metrics.record(outcome, System.nanoTime() - started);
            }
        }
    }
}
