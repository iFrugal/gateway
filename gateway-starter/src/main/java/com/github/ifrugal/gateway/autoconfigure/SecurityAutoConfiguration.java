package com.github.ifrugal.gateway.autoconfigure;

import com.github.ifrugal.gateway.core.config.SecurityProperties;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;

/**
 * Auto-configuration for security features.
 * Enabled when spring-security is on the classpath and gateway.security.enabled=true.
 */
@AutoConfiguration
@ConditionalOnClass(SecurityWebFilterChain.class)
@ConditionalOnProperty(prefix = "gateway.security", name = "enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class SecurityAutoConfiguration {

    private final SecurityProperties securityProperties;

    @Bean
    @ConditionalOnMissingBean(SecurityWebFilterChain.class)
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        List<String> guestPaths = securityProperties.getGuestAllowedPaths();
        String[] guestPathsArray = guestPaths.toArray(new String[0]);

        log.info("Configuring security with {} guest paths", guestPaths.size());

        http.authorizeExchange(exchanges -> {
            exchanges
                    // Allow OPTIONS for CORS preflight
                    .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                    // Allow common paths
                    .pathMatchers("/", "/actuator/**", "/oauth2/**", "/login/**",
                            "/swagger-ui.html", "/swagger-ui/**", "/swagger-resources/**",
                            "/v3/api-docs/**", "/api-docs/**", "/swagger-ui/oauth2-redirect.html"
                    ).permitAll();

            // Add guest paths if any
            if (guestPathsArray.length > 0) {
                exchanges.pathMatchers(guestPathsArray).permitAll();
            }

            exchanges.anyExchange().authenticated();
        });

        // Configure OAuth2 if enabled
        if (securityProperties.getOauth2().isEnabled()) {
            log.info("Configuring OAuth2 security");
            http.oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                    .oauth2Login(oauth2 -> oauth2
                            .authenticationSuccessHandler((webFilterExchange, authentication) -> {
                                ServerHttpResponse response = webFilterExchange.getExchange().getResponse();
                                response.setStatusCode(HttpStatus.FOUND);
                                response.getHeaders().setLocation(URI.create("/swagger-ui.html"));
                                return response.setComplete();
                            })
                    );
        }

        return http
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .csrf(csrf -> csrf.disable())
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .authenticationEntryPoint((exchange, ex) -> {
                            if (isSwaggerUIRequest(exchange.getRequest()) ||
                                    isStaticResourceRequest(exchange.getRequest())) {
                                return Mono.fromRunnable(() -> {
                                    ServerHttpResponse response = exchange.getResponse();
                                    response.setStatusCode(HttpStatus.FOUND);
                                    response.getHeaders().setLocation(
                                            URI.create("/oauth2/authorization/main")
                                    );
                                });
                            } else {
                                return Mono.fromRunnable(() -> {
                                    ServerHttpResponse response = exchange.getResponse();
                                    response.setStatusCode(HttpStatus.UNAUTHORIZED);
                                });
                            }
                        })
                )
                .build();
    }

    private boolean isSwaggerUIRequest(ServerHttpRequest request) {
        String path = request.getURI().getPath();
        return path.startsWith("/swagger-ui") ||
                path.equals("/swagger-ui.html") ||
                path.contains("api-docs") ||
                path.contains("swagger-resources");
    }

    private boolean isStaticResourceRequest(ServerHttpRequest request) {
        String path = request.getURI().getPath();
        return path.startsWith("/css/") ||
                path.startsWith("/js/") ||
                path.startsWith("/images/") ||
                path.startsWith("/webjars/") ||
                path.endsWith(".css") ||
                path.endsWith(".js") ||
                path.endsWith(".png") ||
                path.endsWith(".ico");
    }

    /**
     * Configure OpenAPI with OAuth2 security scheme.
     */
    @Bean
    @ConditionalOnClass(OpenAPI.class)
    @ConditionalOnProperty(prefix = "gateway.security.oauth2", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(OpenAPI.class)
    public OpenAPI customOpenAPI() {
        SecurityProperties.ProviderConfig provider = securityProperties.getOauth2().getProvider();
        SecurityProperties.ClientConfig client = securityProperties.getOauth2().getClient();

        Scopes scopes = new Scopes();
        if (client.getScopes() != null) {
            for (String scope : client.getScopes().split(",")) {
                scopes.addString(scope.trim(), scope.trim());
            }
        }

        return new OpenAPI()
                .components(new Components()
                        .addSecuritySchemes("oauth2", new SecurityScheme()
                                .type(SecurityScheme.Type.OAUTH2)
                                .flows(new OAuthFlows()
                                        .authorizationCode(new OAuthFlow()
                                                .authorizationUrl(provider.getAuthorizationUri())
                                                .tokenUrl(provider.getTokenUri())
                                                .scopes(scopes)
                                        )
                                )
                        )
                )
                .security(List.of(new SecurityRequirement().addList("oauth2")));
    }
}
