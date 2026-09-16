package com.github.ifrugal.gateway.core.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The browser must be able to READ a 401 or 403, or a single-page app can never react to one.
 * That only works if the CORS headers are on the response before Spring Security commits it,
 * which is what the ordering here guarantees.
 */
@DisplayName("OrderedCorsWebFilter")
class OrderedCorsWebFilterTest {

    private static final String ORIGIN = "http://localhost:3000";

    private static CorsConfigurationSource source() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(ORIGIN));
        config.setAllowedMethods(List.of("GET", "POST"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    private static OrderedCorsWebFilter filter() {
        return new OrderedCorsWebFilter(source());
    }

    private static MockServerWebExchange get(String origin) {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.get("http://localhost:8080/api/things");
        if (origin != null) {
            request = request.header(HttpHeaders.ORIGIN, origin);
        }
        return MockServerWebExchange.from(request.build());
    }

    /** Stands in for Spring Security's chain: writes a 401 and commits without calling anything downstream. */
    private static Mono<Void> rejectWith401(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }

    @Test
    @DisplayName("runs ahead of Spring Security's WebFilterChainProxy (order -100)")
    void runsAheadOfSpringSecurity() {
        OrderedCorsWebFilter filter = filter();

        assertThat(filter).isInstanceOf(Ordered.class);
        assertThat(filter.getOrder())
                .isEqualTo(OrderedCorsWebFilter.ORDER)
                .isLessThan(OrderedCorsWebFilter.SPRING_SECURITY_FILTER_ORDER);
    }

    @Test
    @DisplayName("but behind LoggingAndCachingWebFilter, which must see the untouched request")
    void runsBehindTheLoggingFilter() {
        assertThat(filter().getOrder()).isGreaterThan(Ordered.HIGHEST_PRECEDENCE);
    }

    @Test
    @DisplayName("a custom order is honoured")
    void customOrder() {
        assertThat(new OrderedCorsWebFilter(source(), 42).getOrder()).isEqualTo(42);
    }

    @Test
    @DisplayName("a 401 written downstream still carries the CORS headers")
    void rejectedResponseCarriesCorsHeaders() {
        MockServerWebExchange exchange = get(ORIGIN);

        StepVerifier.create(filter().filter(exchange, OrderedCorsWebFilterTest::rejectWith401))
                .verifyComplete();

        HttpHeaders headers = exchange.getResponse().getHeaders();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(headers.getAccessControlAllowOrigin()).isEqualTo(ORIGIN);
        assertThat(headers.getAccessControlAllowCredentials()).isTrue();
        assertThat(headers.getVary()).contains(HttpHeaders.ORIGIN);
    }

    @Test
    @DisplayName("a preflight is answered here and never reaches the rest of the chain")
    void preflightAnsweredWithoutDelegating() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .options("http://localhost:8080/api/things")
                .header(HttpHeaders.ORIGIN, ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .build());
        AtomicBoolean delegated = new AtomicBoolean();
        WebFilterChain chain = ex -> {
            delegated.set(true);
            return Mono.empty();
        };

        StepVerifier.create(filter().filter(exchange, chain)).verifyComplete();

        HttpHeaders headers = exchange.getResponse().getHeaders();
        assertThat(delegated).isFalse();
        assertThat(headers.getAccessControlAllowOrigin()).isEqualTo(ORIGIN);
        assertThat(headers.getAccessControlAllowMethods()).contains(org.springframework.http.HttpMethod.POST);
    }

    @Test
    @DisplayName("an origin outside the policy is refused with 403 before anything downstream runs")
    void unknownOriginRefused() {
        MockServerWebExchange exchange = get("https://not-ours.example");
        AtomicBoolean delegated = new AtomicBoolean();
        WebFilterChain chain = ex -> {
            delegated.set(true);
            return Mono.empty();
        };

        StepVerifier.create(filter().filter(exchange, chain)).verifyComplete();

        assertThat(delegated).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange.getResponse().getHeaders().getAccessControlAllowOrigin()).isNull();
    }

    @Test
    @DisplayName("a same-origin request (no Origin header) passes through untouched")
    void sameOriginPassesThrough() {
        MockServerWebExchange exchange = get(null);
        AtomicBoolean delegated = new AtomicBoolean();
        WebFilterChain chain = ex -> {
            delegated.set(true);
            return Mono.empty();
        };

        StepVerifier.create(filter().filter(exchange, chain)).verifyComplete();

        assertThat(delegated).isTrue();
        assertThat(exchange.getResponse().getHeaders().getAccessControlAllowOrigin()).isNull();
    }
}
