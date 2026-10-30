package com.agenticsdlc.shortener.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.context.annotation.Configuration;

import com.agenticsdlc.shortener.api.ApiHeaders;
import com.agenticsdlc.shortener.api.OpenApiNames;

/**
 * OpenAPI metadata for the springdoc-generated document ({@code /v3/api-docs}, UI at {@code /swagger-ui.html}).
 * The committed contract is {@code docs/api/url-shortener.openapi.yaml}; {@code OpenApiContractTest} keeps both
 * in sync.
 */
@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(
        info = @Info(
                title = "URL Shortener API",
                version = "v1",
                description = "Create short links, follow redirects and read click analytics. "
                        + "Errors are RFC 9457 Problem Details.",
                license = @License(name = "Apache-2.0")),
        tags = {
            @Tag(name = "Links", description = "Short link management"),
            @Tag(name = "Analytics", description = "Aggregated click analytics"),
            @Tag(name = "Redirect", description = "Short link resolution")
        })
@SecurityScheme(name = OpenApiNames.API_KEY_SCHEME, type = SecuritySchemeType.APIKEY, in = SecuritySchemeIn.HEADER,
        paramName = ApiHeaders.API_KEY,
        description = "Required on /api/v1/links/** only when shortener.security.api-key is configured")
class OpenApiConfig {
}
