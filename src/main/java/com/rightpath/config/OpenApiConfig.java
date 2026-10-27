package com.rightpath.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;

/**
 * OpenAPI / Swagger UI configuration.
 *
 * <p>Active for every profile except {@code prod} (i.e. {@code dev}, {@code stage},
 * and the default profile). In {@code prod} both the API docs and the Swagger UI are
 * additionally switched off via {@code springdoc.*.enabled=false} in
 * {@code application-prod.properties}, so the endpoints are not exposed publicly.</p>
 *
 * <p>Swagger UI:  {@code /swagger-ui.html}<br>
 * OpenAPI JSON: {@code /v3/api-docs}</p>
 *
 * <p>Use the <b>Authorize</b> button and paste a JWT access token obtained from
 * {@code POST /api/login} to call secured endpoints.</p>
 */
@Configuration
@Profile("!prod")
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Value("${app.base.url:http://localhost:8082}")
    private String baseUrl;

    @Bean
    public OpenAPI rightpathOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("AI-RightPath Backend API")
                        .version("v1")
                        .description("REST API for the AI-RightPath recruitment, assessment and "
                                + "AI-interview platform. Authenticate via POST /api/login, then use "
                                + "the Authorize button to send the JWT access token as a Bearer token.")
                        .contact(new Contact().name("AI-RightPath").email("info@n-visionsoft.com"))
                        .license(new License().name("Proprietary")))
                .servers(List.of(new Server().url(baseUrl).description("Current environment")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .name(BEARER_SCHEME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("JWT access token from POST /api/login (send as: Bearer <token>).")));
    }
}
