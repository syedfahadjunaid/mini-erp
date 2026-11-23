package com.minierp.shared;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The OpenAPI description at {@code /v3/api-docs} and Swagger UI at {@code /swagger-ui.html}.
 * Paste an access token (from {@code scripts/get-token.ps1}) into "Authorize" to try calls.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    @Bean
    OpenAPI erpOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Mini ERP API")
                        .version("v1")
                        .description("Purchasing, multi-warehouse stock ledger, sales, invoicing and reports."))
                .components(new Components().addSecuritySchemes("bearer", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
