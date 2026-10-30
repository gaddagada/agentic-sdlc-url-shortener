package com.agenticsdlc.shortener.api;

import java.net.URI;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.agenticsdlc.shortener.api.dto.CreateLinkRequest;
import com.agenticsdlc.shortener.api.dto.LinkResponse;
import com.agenticsdlc.shortener.service.CreatedLink;
import com.agenticsdlc.shortener.service.LinkService;

/**
 * Short link management: create, read and deactivate.
 */
@RestController
@RequestMapping(LinkController.BASE_PATH)
@Tag(name = "Links")
@SecurityRequirement(name = OpenApiNames.API_KEY_SCHEME)
public class LinkController {

    /** Base path of the links resource. */
    public static final String BASE_PATH = "/api/v1/links";
    /** Optional request header making creation safe to retry. */
    public static final String IDEMPOTENCY_KEY_HEADER = ApiHeaders.IDEMPOTENCY_KEY;
    /** Response header set to {@code true} when a stored response is replayed. */
    public static final String IDEMPOTENT_REPLAYED_HEADER = ApiHeaders.IDEMPOTENT_REPLAYED;

    private final LinkService linkService;

    /**
     * Creates the controller.
     *
     * @param linkService link lifecycle service
     */
    public LinkController(LinkService linkService) {
        this.linkService = linkService;
    }

    /**
     * Creates a short link.
     *
     * @param request        the link to create
     * @param idempotencyKey optional idempotency key
     * @return {@code 201 Created} with {@code Location} and the link
     */
    @Operation(operationId = "createLink", summary = "Create a short link",
            description = "Creates a short link with a random Base62 code or a custom alias. With an "
                    + "Idempotency-Key, a retried request with the same payload within 24 h returns the original "
                    + "response (Idempotent-Replayed: true); the same key with a different payload is rejected.")
    @ApiResponse(responseCode = "201", description = "Link created (or replayed)", headers = {
        @Header(name = "Location", description = "URI of the created link resource",
                schema = @Schema(type = "string", format = "uri-reference")),
        @Header(name = IDEMPOTENT_REPLAYED_HEADER, description = "Present with value true on idempotent replays",
                schema = @Schema(type = "boolean")),
        @Header(name = ApiHeaders.RATE_LIMIT_LIMIT, description = "Bucket capacity",
                schema = @Schema(type = "integer")),
        @Header(name = ApiHeaders.RATE_LIMIT_REMAINING, description = "Tokens left",
                schema = @Schema(type = "integer"))})
    @ApiResponse(responseCode = "400", description = "Validation error or invalid expiry",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Missing or wrong X-Api-Key (only when an API key is configured)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Alias already taken, or same Idempotency-Key in flight",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422",
            description = "URL rejected by policy, alias reserved, or Idempotency-Key reused with another payload",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "Rate limit exceeded", headers =
        @Header(name = "Retry-After", description = "Seconds to wait", schema = @Schema(type = "integer")),
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "No free short code could be allocated",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LinkResponse> create(
            @Valid @RequestBody CreateLinkRequest request,
            @Parameter(description = "Client-generated key (1-128 characters) making the request safe to retry")
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) @Size(min = 1, max = 128)
            @Nullable String idempotencyKey) {
        CreatedLink created = linkService.create(request.toCommand(), idempotencyKey);
        LinkResponse body = LinkResponse.from(created.link());
        ResponseEntity.BodyBuilder response = ResponseEntity.created(URI.create(BASE_PATH + "/" + body.code()));
        if (created.replayed()) {
            response.header(IDEMPOTENT_REPLAYED_HEADER, "true");
        }
        return response.body(body);
    }

    /**
     * Returns link metadata.
     *
     * @param code short code
     * @return the link
     */
    @Operation(operationId = "getLink", summary = "Get link metadata")
    @ApiResponse(responseCode = "200", description = "The link")
    @ApiResponse(responseCode = "401", description = "Missing or wrong X-Api-Key (only when an API key is configured)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Unknown code",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping(path = "/{code}", produces = MediaType.APPLICATION_JSON_VALUE)
    public LinkResponse get(@PathVariable String code) {
        return LinkResponse.from(linkService.get(code));
    }

    /**
     * Deactivates (soft-deletes) a link. Idempotent.
     *
     * @param code short code
     * @return {@code 204 No Content}
     */
    @Operation(operationId = "deactivateLink", summary = "Deactivate a link",
            description = "Soft delete: the link keeps its analytics, its status becomes DISABLED and redirects "
                    + "answer 410 Gone. Idempotent.")
    @ApiResponse(responseCode = "204", description = "Link deactivated")
    @ApiResponse(responseCode = "401", description = "Missing or wrong X-Api-Key (only when an API key is configured)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Unknown code",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @DeleteMapping("/{code}")
    public ResponseEntity<Void> deactivate(@PathVariable String code) {
        linkService.deactivate(code);
        return ResponseEntity.noContent().build();
    }
}
