package com.agenticsdlc.shortener.api.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.agenticsdlc.shortener.service.BulkLinkService;
import com.agenticsdlc.shortener.service.CreateLinkCommand;

/**
 * Request body of {@code POST /api/v1/links/bulk}.
 *
 * @param items the links to create, 1 to {@value BulkLinkService#MAX_ITEMS}, each validated like the body of
 *              {@code POST /api/v1/links} (errors are reported as {@code items[i].field})
 */
@Schema(name = "BulkCreateLinksRequest", description = "Short links to create in one request")
public record BulkCreateLinksRequest(
        @ArraySchema(arraySchema = @Schema(description = "Links to create, processed in order",
                requiredMode = Schema.RequiredMode.REQUIRED), minItems = 1, maxItems = BulkLinkService.MAX_ITEMS)
        @NotNull @Size(min = 1, max = BulkLinkService.MAX_ITEMS) List<@NotNull @Valid CreateLinkRequest> items) {

    /** {@return the transport-independent commands, in request order} */
    public List<CreateLinkCommand> toCommands() {
        return items.stream().map(CreateLinkRequest::toCommand).toList();
    }
}
