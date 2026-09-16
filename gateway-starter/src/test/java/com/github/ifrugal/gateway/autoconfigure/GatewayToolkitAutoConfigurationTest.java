package com.github.ifrugal.gateway.autoconfigure;

import com.github.ifrugal.gateway.core.cache.CacheProvider;
import com.github.ifrugal.gateway.core.cache.CaffeineProvider;
import com.github.ifrugal.gateway.core.cache.NoOpCacheProvider;
import com.github.ifrugal.gateway.core.filter.LoggingAndCachingWebFilter;
import com.github.ifrugal.gateway.core.filter.OrderedCorsWebFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GatewayToolkitAutoConfiguration")
class GatewayToolkitAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(GatewayToolkitAutoConfiguration.class));

    @Test
    @DisplayName("should register NoOpCacheProvider when caching is disabled")
    void noOpCacheWhenDisabled() {
        contextRunner
                .withPropertyValues("gateway.caching.enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(CacheProvider.class);
                    assertThat(context.getBean(CacheProvider.class)).isInstanceOf(NoOpCacheProvider.class);
                });
    }

    @Test
    @DisplayName("should register CaffeineProvider when caching is enabled")
    void caffeineWhenEnabled() {
        contextRunner
                .withPropertyValues("gateway.caching.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(CacheProvider.class);
                    assertThat(context.getBean(CacheProvider.class)).isInstanceOf(CaffeineProvider.class);
                });
    }

    @Test
    @DisplayName("should register LoggingAndCachingWebFilter")
    void webFilterRegistered() {
        contextRunner
                .run(context -> {
                    assertThat(context).hasSingleBean(LoggingAndCachingWebFilter.class);
                });
    }

    @Test
    @DisplayName("should not register Conman beans when conman is disabled")
    void noConmanWhenDisabled() {
        contextRunner
                .withPropertyValues("gateway.conman.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean("conmanCache");
                    assertThat(context).doesNotHaveBean("conmanServlet");
                });
    }

    @Nested
    @DisplayName("CORS")
    class Cors {

        /** Stands in for Spring Security's chain: writes a 401 and commits without going further. */
        private Mono<Void> rejectWith401(ServerWebExchange exchange) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        private MockServerWebExchange getFrom(String origin) {
            return MockServerWebExchange.from(MockServerHttpRequest.get("http://localhost:8080/api/things")
                    .header(HttpHeaders.ORIGIN, origin)
                    .build());
        }

        @Test
        @DisplayName("registers the policy as a CorsConfigurationSource and the filter ahead of Spring Security")
        void filterOrderedAheadOfSpringSecurity() {
            contextRunner.run(context -> {
                assertThat(context).hasSingleBean(CorsConfigurationSource.class);
                assertThat(context).hasBean("gatewayCorsConfigurationSource");
                assertThat(context).hasBean("gatewayCorsFilter");

                Object filter = context.getBean("gatewayCorsFilter");
                assertThat(filter).isInstanceOf(OrderedCorsWebFilter.class);
                assertThat(((Ordered) filter).getOrder())
                        .isLessThan(OrderedCorsWebFilter.SPRING_SECURITY_FILTER_ORDER);
            });
        }

        @Test
        @DisplayName("registers neither bean when gateway.cors.enabled=false")
        void nothingWhenDisabled() {
            contextRunner
                    .withPropertyValues("gateway.cors.enabled=false")
                    .run(context -> {
                        assertThat(context).doesNotHaveBean("gatewayCorsFilter");
                        assertThat(context).doesNotHaveBean(CorsConfigurationSource.class);
                    });
        }

        @Test
        @DisplayName("applies gateway.cors.* to a 401 written further down the chain")
        void policyFromPropertiesReachesARejectedResponse() {
            contextRunner
                    .withPropertyValues(
                            "gateway.cors.allowed-origins=https://app.example",
                            "gateway.cors.allow-credentials=false",
                            "gateway.cors.exposed-headers=x-request-id")
                    .run(context -> {
                        WebFilter filter = context.getBean("gatewayCorsFilter", WebFilter.class);
                        MockServerWebExchange exchange = getFrom("https://app.example");

                        StepVerifier.create(filter.filter(exchange, this::rejectWith401)).verifyComplete();

                        HttpHeaders headers = exchange.getResponse().getHeaders();
                        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                        assertThat(headers.getAccessControlAllowOrigin()).isEqualTo("https://app.example");
                        assertThat(headers.getAccessControlAllowCredentials()).isFalse();
                        assertThat(headers.getAccessControlExposeHeaders()).containsExactly("x-request-id");
                    });
        }

        @Test
        @DisplayName("a user-declared CorsConfigurationSource replaces the toolkit's and drives the bundled filter")
        void userDeclaredSourceWins() {
            contextRunner
                    .withUserConfiguration(CustomCorsPolicy.class)
                    .withPropertyValues("gateway.cors.allowed-origins=https://toolkit.example")
                    .run(context -> {
                        assertThat(context).hasSingleBean(CorsConfigurationSource.class);
                        assertThat(context).doesNotHaveBean("gatewayCorsConfigurationSource");

                        WebFilter filter = context.getBean("gatewayCorsFilter", WebFilter.class);
                        MockServerWebExchange exchange = getFrom("https://custom.example");

                        StepVerifier.create(filter.filter(exchange, this::rejectWith401)).verifyComplete();

                        assertThat(exchange.getResponse().getHeaders().getAccessControlAllowOrigin())
                                .isEqualTo("https://custom.example");
                    });
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomCorsPolicy {

        @Bean
        CorsConfigurationSource customCorsPolicy() {
            CorsConfiguration config = new CorsConfiguration();
            config.setAllowedOrigins(List.of("https://custom.example"));
            config.setAllowedMethods(List.of("GET"));
            UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
            source.registerCorsConfiguration("/**", config);
            return source;
        }
    }
}
