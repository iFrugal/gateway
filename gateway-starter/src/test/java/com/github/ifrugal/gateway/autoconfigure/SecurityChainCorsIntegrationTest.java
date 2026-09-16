package com.github.ifrugal.gateway.autoconfigure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.webflux.autoconfigure.WebFluxAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The real thing, end to end: the toolkit's auto-configuration, Spring Security's
 * {@code WebFilterChainProxy}, and WebFlux's handler, with a browser-style cross-origin request
 * that carries no credential. Spring Security writes the 401 itself; the browser must still be
 * able to read it, which means the CORS headers have to be on it.
 *
 * <p>Before the fix this test documents, the CORS filter ran behind Spring Security and the 401
 * went out with no {@code Access-Control-Allow-Origin} at all.
 */
@DisplayName("CORS headers on responses Spring Security writes itself")
class SecurityChainCorsIntegrationTest {

    private static final String ORIGIN = "http://localhost:3000";

    private final ReactiveWebApplicationContextRunner runner = new ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    WebFluxAutoConfiguration.class,
                    GatewayToolkitAutoConfiguration.class,
                    SecurityAutoConfiguration.class))
            .withUserConfiguration(SpringSecurity.class)
            .withPropertyValues(
                    "gateway.security.enabled=true",
                    "gateway.security.oauth2.enabled=false",
                    "gateway.logging.enabled=false",
                    "gateway.cors.allowed-origins=" + ORIGIN);

    /**
     * Absolute URLs, as a real server sees them: Spring's same-origin check needs the request's
     * scheme and host to decide that {@code http://localhost:3000} is a different origin.
     */
    private static WebTestClient client(org.springframework.context.ApplicationContext context) {
        return WebTestClient.bindToApplicationContext(context)
                .configureClient()
                .baseUrl("http://localhost:8080")
                .build();
    }

    /** What Spring Boot's reactive security auto-configuration imports for a real application. */
    @Configuration(proxyBeanMethods = false)
    @EnableWebFluxSecurity
    static class SpringSecurity {
    }

    @Test
    @DisplayName("a 401 for a missing credential carries Access-Control-Allow-Origin")
    void unauthorizedResponseCarriesCorsHeaders() {
        runner.run(context -> client(context)
                .get().uri("/api/protected")
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN)
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    @Test
    @DisplayName("a preflight is answered without a credential")
    void preflightSucceedsWithoutCredential() {
        runner.run(context -> client(context)
                .options().uri("/api/protected")
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN)
                .expectHeader().exists(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS));
    }

    @Test
    @DisplayName("a same-origin request gets a plain 401 with no CORS headers")
    void sameOriginUnauthorizedIsUntouched() {
        runner.run(context -> client(context)
                .get().uri("/api/protected")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    @DisplayName("an origin outside the policy is refused with 403, not leaked a 401")
    void unknownOriginRefused() {
        runner.run(context -> client(context)
                .get().uri("/api/protected")
                .header(HttpHeaders.ORIGIN, "https://not-ours.example")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }
}
