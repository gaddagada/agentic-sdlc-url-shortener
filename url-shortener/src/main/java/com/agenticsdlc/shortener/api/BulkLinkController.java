package com.agenticsdlc.shortener.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.agenticsdlc.shortener.api.dto.BulkCreateLinksRequest;
import com.agenticsdlc.shortener.api.dto.BulkCreateLinksResponse;
import com.agenticsdlc.shortener.service.BulkCreationResult;
import com.agenticsdlc.shortener.service.BulkLinkService;
import com.agenticsdlc.shortener.service.ClientIpResolver;

/**
 * Bulk link creation: {@code POST /api/v1/links/bulk} creates 1 to {@value BulkLinkService#MAX_ITEMS} links, each
 * independently (partial failure), see {@link BulkLinkService}.
 *
 * <p>The request is answered {@code 200 OK} whenever it was executed, even if every item failed: the outcome of each
 * item is in {@code results}. Errors of the request as a whole (validation, rate limit, batch larger than the
 * rate-limit capacity, idempotency conflicts, authentication) are Problem Details like on every other endpoint.
 *
 * <p>Rate limiting: this endpoint is exempt from the per-request charge of the rate-limit filter; the service charges
 * one token per item after the body has been parsed and validated.
 */
@RestController
@RequestMapping(LinkController.BASE_PATH)
@Tag(name = "Links")
@SecurityRequirement(name = OpenApiNames.API_KEY_SCHEME)
public class BulkLinkController {

    /** Path of the bulk endpoint. */
    public static final String PATH = LinkController.BASE_PATH + "/bulk";

    private final BulkLinkService bulkLinkService;
    private final ClientIpResolver clientIpResolver;

    /**
     * Creates the controller.
     *
     * @param bulkLinkService  bulk creation
     * @param clientIpResolver resolves the rate-limit key of the caller
     */
    public BulkLinkController(BulkLinkService bulkLinkService, ClientIpResolver clientIpResolver) {
        this.bulkLinkService = bulkLinkService;
        this.clientIpResolver = clientIpResolver;
    }

    /**
     * Creates the links of a batch.
     *
     * @param request        the links to create
     * @param idempotencyKey optional idempotency key
     * @param httpRequest    current request (client IP)
     * @return {@code 200 OK} with one result per item
     */
    @Operation(operationId = "createLinksBulk", summary = "Create up to 100 short links",
            description = "Creates every item like POST /api/v1/links, each in its own transaction: a failing item "
                    + "never affects the others and reports the Problem Details type and status the single-link "
                    + "endpoint would have answered. Later items repeating an alias of an earlier item fail with "
                    + "alias-conflict. Charged one rate-limit token per item. With an Idempotency-Key the whole "
                    + "response is replayed for a retry with the same items within 24 h.")
    @ApiResponse(responseCode = "200", description = "Batch executed; see results for the outcome of each item",
            headers = {
                @Header(name = ApiHeaders.IDEMPOTENT_REPLAYED,
                        description = "Present with value true on idempotent replays",
                        schema = @Schema(type = "boolean")),
                @Header(name = ApiHeaders.RATE_LIMIT_LIMIT, description = "Bucket capacity",
                        schema = @Schema(type = "integer")),
                @Header(name = ApiHeaders.RATE_LIMIT_REMAINING, description = "Tokens left after this request",
                        schema = @Schema(type = "integer"))})
    @ApiResponse(responseCode = "400", description = "Validation error (0 or more than 100 items, invalid item) or "
            + "malformed JSON",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Missing or wrong X-Api-Key (only when an API key is configured)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Same Idempotency-Key in flight",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422",
            description = "More items than the rate-limit capacity, or Idempotency-Key reused with other items",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "Not enough rate-limit tokens for all items", headers =
        @Header(name = "Retry-After", description = "Seconds to wait", schema = @Schema(type = "integer")),
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(path = "/bulk", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<BulkCreateLinksResponse> createBulk(
            @Valid @RequestBody BulkCreateLinksRequest request,
            @Parameter(description = "Client-generated key (1-128 characters) making the request safe to retry")
            @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) @Size(min = 1, max = 128)
            @Nullable String idempotencyKey,
            HttpServletRequest httpRequest) {
        String client = clientIpResolver.resolve(httpRequest.getRemoteAddr(),
                httpRequest.getHeader(ClientIpResolver.FORWARDED_FOR));
        BulkCreationResult result = bulkLinkService.createAll(request.toCommands(), idempotencyKey, client);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (result.replayed()) {
            response.header(ApiHeaders.IDEMPOTENT_REPLAYED, "true");
        }
        BulkCreationResult.RateLimitStatus rateLimit = result.rateLimit();
        if (rateLimit != null) {
            response.header(ApiHeaders.RATE_LIMIT_LIMIT, Integer.toString(rateLimit.limit()));
            response.header(ApiHeaders.RATE_LIMIT_REMAINING, Long.toString(rateLimit.remaining()));
        }
        return response.body(BulkCreateLinksResponse.from(result.creation()));
    }
}
