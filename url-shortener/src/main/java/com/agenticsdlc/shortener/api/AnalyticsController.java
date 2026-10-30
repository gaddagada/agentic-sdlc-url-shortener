package com.agenticsdlc.shortener.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.agenticsdlc.shortener.api.dto.AnalyticsResponse;
import com.agenticsdlc.shortener.service.AnalyticsService;

/**
 * Aggregated click analytics of a link.
 */
@RestController
@RequestMapping(LinkController.BASE_PATH)
@Tag(name = "Analytics")
@SecurityRequirement(name = OpenApiNames.API_KEY_SCHEME)
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    /**
     * Creates the controller.
     *
     * @param analyticsService analytics aggregation
     */
    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    /**
     * Returns analytics over the last {@code days} UTC days (including today).
     *
     * @param code short code
     * @param days window size, 1..90, default 7
     * @return aggregated analytics
     */
    @Operation(operationId = "getLinkAnalytics", summary = "Get click analytics",
            description = "Totals, unique visitors, clicks per UTC day (zero-filled), top 10 referrers and clicks "
                    + "per user-agent family. Clicks are recorded asynchronously and appear within moments.")
    @ApiResponse(responseCode = "200", description = "Aggregated analytics")
    @ApiResponse(responseCode = "400", description = "days outside 1..90",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Missing or wrong X-Api-Key (only when an API key is configured)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Unknown code",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping(path = "/{code}/analytics", produces = MediaType.APPLICATION_JSON_VALUE)
    public AnalyticsResponse analytics(
            @PathVariable String code,
            @Parameter(description = "Window size in days (1..90)")
            @RequestParam(name = "days", defaultValue = "7")
            @Min(AnalyticsService.MIN_WINDOW_DAYS) @Max(AnalyticsService.MAX_WINDOW_DAYS) int days) {
        return AnalyticsResponse.from(analyticsService.analyticsFor(code, days));
    }
}
