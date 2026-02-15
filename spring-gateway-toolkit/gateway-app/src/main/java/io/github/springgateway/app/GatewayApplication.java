package io.github.springgateway.app;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.method.HandlerMethod;

/**
 * Spring Cloud Gateway Application.
 *
 * This application provides:
 * - API Gateway with load balancing and service discovery
 * - Request/Response logging
 * - Response caching
 * - Mock API framework (Conman)
 * - OAuth2 security
 * - Swagger/OpenAPI aggregation
 *
 * All features are configurable via application.yml or environment variables.
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }

    /**
     * Customize OpenAPI operations to add common headers.
     */
    @Bean
    public OperationCustomizer customGlobalHeaders() {
        return (Operation operation, HandlerMethod handlerMethod) -> {
            operation.addParametersItem(createHeaderParameter("x-request-id", "Unique request identifier"));
            operation.addParametersItem(createHeaderParameter("x-user-id", "ID of the authenticated user"));
            operation.addParametersItem(createHeaderParameter("x-role", "User role"));
            return operation;
        };
    }

    private Parameter createHeaderParameter(String name, String description) {
        return new Parameter()
                .in("header")
                .name(name)
                .description(description)
                .schema(new StringSchema());
    }
}
