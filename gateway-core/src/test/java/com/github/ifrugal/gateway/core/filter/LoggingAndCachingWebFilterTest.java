package com.github.ifrugal.gateway.core.filter;

import com.github.ifrugal.gateway.core.cache.CacheProvider;
import com.github.ifrugal.gateway.core.cache.CaffeineProvider;
import com.github.ifrugal.gateway.core.cache.NoOpCacheProvider;
import com.github.ifrugal.gateway.core.config.CachingProperties;
import com.github.ifrugal.gateway.core.config.LoggingProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LoggingAndCachingWebFilter")
class LoggingAndCachingWebFilterTest {

    private final CacheProvider noOpCache = new NoOpCacheProvider();

    private LoggingAndCachingWebFilter createFilter(LoggingProperties loggingProps,
                                                     CachingProperties cachingProps,
                                                     CacheProvider cacheProvider) {
        return new LoggingAndCachingWebFilter(loggingProps, cachingProps, cacheProvider);
    }

    private LoggingAndCachingWebFilter createFilterWithIgnorePaths(LoggingProperties loggingProps,
                                                                    CachingProperties cachingProps,
                                                                    CacheProvider cacheProvider,
                                                                    List<String> ignorePaths) {
        return new LoggingAndCachingWebFilter(loggingProps, cachingProps, cacheProvider, ignorePaths);
    }

    @Test
    @DisplayName("should have highest precedence order")
    void highestPrecedence() {
        LoggingAndCachingWebFilter filter = createFilter(
                new LoggingProperties(), new CachingProperties(), noOpCache);
        assertThat(filter.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    @Nested
    @DisplayName("Default ignore paths")
    class DefaultIgnorePaths {

        @Test
        @DisplayName("should skip actuator health path")
        void skipsActuatorHealth() {
            LoggingAndCachingWebFilter filter = createFilter(
                    new LoggingProperties(), new CachingProperties(), noOpCache);

            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/actuator/health").build());

            WebFilterChain chain = ex -> Mono.empty();

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();
        }

        @Test
        @DisplayName("should skip swagger-ui paths")
        void skipsSwaggerUi() {
            LoggingAndCachingWebFilter filter = createFilter(
                    new LoggingProperties(), new CachingProperties(), noOpCache);

            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/swagger-ui/index.html").build());

            WebFilterChain chain = ex -> Mono.empty();

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();
        }

        @Test
        @DisplayName("should skip v3/api-docs paths")
        void skipsApiDocs() {
            LoggingAndCachingWebFilter filter = createFilter(
                    new LoggingProperties(), new CachingProperties(), noOpCache);

            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/v3/api-docs/swagger-config").build());

            AtomicBoolean chainCalled = new AtomicBoolean(false);
            WebFilterChain chain = ex -> {
                chainCalled.set(true);
                return Mono.empty();
            };

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertThat(chainCalled.get()).isTrue();
        }
    }

    @Nested
    @DisplayName("Configurable ignore paths")
    class ConfigurableIgnorePaths {

        @Test
        @DisplayName("should use custom ignore paths when provided")
        void customIgnorePaths() {
            List<String> customPaths = List.of("/health/**", "/internal/**");
            LoggingAndCachingWebFilter filter = createFilterWithIgnorePaths(
                    new LoggingProperties(), new CachingProperties(), noOpCache, customPaths);

            // Custom path should be ignored
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/health/ready").build());
            WebFilterChain chain = ex -> Mono.empty();

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();
        }

        @Test
        @DisplayName("should not skip default paths when custom paths are used")
        void customPathsReplaceDefaults() {
            List<String> customPaths = List.of("/health/**");
            LoggingProperties loggingProps = new LoggingProperties();
            loggingProps.setEnabled(true);
            LoggingProperties.RequestConfig config = new LoggingProperties.RequestConfig();
            config.setPaths(List.of("/**"));
            config.setMethods(List.of("*"));
            config.setExcludeBody(true);
            loggingProps.setRequests(List.of(config));

            LoggingAndCachingWebFilter filter = createFilterWithIgnorePaths(
                    loggingProps, new CachingProperties(), noOpCache, customPaths);

            // /actuator/health is NOT in custom ignore list, so it should be processed
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/actuator/health").build());

            AtomicBoolean chainCalled = new AtomicBoolean(false);
            WebFilterChain chain = ex -> {
                chainCalled.set(true);
                return Mono.empty();
            };

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            // Chain is called because /actuator/health matches the logging config "/**"
            // and is NOT in the custom ignore list
            assertThat(chainCalled.get()).isTrue();
        }

        @Test
        @DisplayName("should use defaults when ignorePaths is null")
        void nullIgnorePathsUsesDefaults() {
            LoggingAndCachingWebFilter filter = createFilterWithIgnorePaths(
                    new LoggingProperties(), new CachingProperties(), noOpCache, null);

            // Default path should still be ignored
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/actuator/health").build());
            WebFilterChain chain = ex -> Mono.empty();

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();
        }

        @Test
        @DisplayName("should use defaults when ignorePaths is empty")
        void emptyIgnorePathsUsesDefaults() {
            LoggingAndCachingWebFilter filter = createFilterWithIgnorePaths(
                    new LoggingProperties(), new CachingProperties(), noOpCache, List.of());

            // Default path should still be ignored
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/actuator/health").build());
            WebFilterChain chain = ex -> Mono.empty();

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Cache key generation")
    class CacheKeyGeneration {

        @Test
        @DisplayName("should generate key with path only (no query params)")
        void keyWithPathOnly() {
            LoggingAndCachingWebFilter filter = createFilter(
                    new LoggingProperties(), new CachingProperties(), noOpCache);

            MockServerHttpRequest request = MockServerHttpRequest.get("/api/products").build();
            String key = filter.generateCacheKey(request);

            assertThat(key).isEqualTo("GET:/api/products");
        }

        @Test
        @DisplayName("should include sorted query parameters in cache key")
        void keyWithSortedQueryParams() {
            LoggingAndCachingWebFilter filter = createFilter(
                    new LoggingProperties(), new CachingProperties(), noOpCache);

            MockServerHttpRequest request = MockServerHttpRequest
                    .get("/api/products?sort=name&category=electronics&page=1").build();
            String key = filter.generateCacheKey(request);

            // Parameters should be sorted alphabetically
            assertThat(key).isEqualTo("GET:/api/products?category=electronics&page=1&sort=name");
        }

        @Test
        @DisplayName("should produce same key regardless of query param order")
        void keyDeterministic() {
            LoggingAndCachingWebFilter filter = createFilter(
                    new LoggingProperties(), new CachingProperties(), noOpCache);

            MockServerHttpRequest request1 = MockServerHttpRequest
                    .get("/api/data?b=2&a=1").build();
            MockServerHttpRequest request2 = MockServerHttpRequest
                    .get("/api/data?a=1&b=2").build();

            String key1 = filter.generateCacheKey(request1);
            String key2 = filter.generateCacheKey(request2);

            assertThat(key1).isEqualTo(key2);
        }

        @Test
        @DisplayName("should use POST method in key")
        void keyWithPostMethod() {
            LoggingAndCachingWebFilter filter = createFilter(
                    new LoggingProperties(), new CachingProperties(), noOpCache);

            MockServerHttpRequest request = MockServerHttpRequest.post("/api/orders").build();
            String key = filter.generateCacheKey(request);

            assertThat(key).isEqualTo("POST:/api/orders");
        }
    }

    @Test
    @DisplayName("should pass through when neither logging nor caching matches")
    void passThroughWhenNoMatch() {
        LoggingProperties loggingProps = new LoggingProperties();
        loggingProps.setEnabled(true);
        loggingProps.setRequests(List.of());

        CachingProperties cachingProps = new CachingProperties();
        cachingProps.setEnabled(false);

        LoggingAndCachingWebFilter filter = createFilter(loggingProps, cachingProps, noOpCache);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/some/path").build());

        WebFilterChain chain = ex -> Mono.empty();

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();
    }

    @Test
    @DisplayName("should add x-request-id header when missing")
    void addsRequestIdWhenMissing() {
        LoggingProperties loggingProps = new LoggingProperties();
        loggingProps.setEnabled(true);
        LoggingProperties.RequestConfig config = new LoggingProperties.RequestConfig();
        config.setPaths(List.of("/api/**"));
        config.setMethods(List.of("*"));
        config.setExcludeBody(true);
        loggingProps.setRequests(List.of(config));

        CachingProperties cachingProps = new CachingProperties();
        cachingProps.setEnabled(false);

        LoggingAndCachingWebFilter filter = createFilter(loggingProps, cachingProps, noOpCache);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/users").build());

        WebFilterChain chain = ex -> {
            String requestId = ex.getRequest().getHeaders().getFirst("x-request-id");
            assertThat(requestId).isNotNull().isNotEmpty();
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();
    }

    @Test
    @DisplayName("should short-circuit on cache hit without calling upstream")
    void shortCircuitOnCacheHit() {
        CachingProperties cachingProps = new CachingProperties();
        cachingProps.setEnabled(true);
        cachingProps.setMaxSize(100);
        cachingProps.setDefaultTtl(3600);
        CachingProperties.CacheRuleConfig rule = new CachingProperties.CacheRuleConfig();
        rule.setPaths(List.of("/api/**"));
        rule.setMethods(List.of("*"));
        rule.setTtl(300);
        cachingProps.setRules(List.of(rule));

        CaffeineProvider cacheProvider = new CaffeineProvider(cachingProps);

        // Pre-populate cache with the key that generateCacheKey() now produces
        cacheProvider.put("GET:/api/data", "{\"cached\":true}", 300).block();

        LoggingProperties loggingProps = new LoggingProperties();
        loggingProps.setEnabled(false);

        LoggingAndCachingWebFilter filter = createFilter(loggingProps, cachingProps, cacheProvider);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/data").build());

        WebFilterChain chain = ex -> {
            throw new AssertionError("Chain should not be called on cache hit");
        };

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getHeaders().getFirst("X-Cache")).isEqualTo("HIT");
    }

    @Test
    @DisplayName("should call upstream on cache miss")
    void cacheMissCallsUpstream() {
        CachingProperties cachingProps = new CachingProperties();
        cachingProps.setEnabled(true);
        cachingProps.setMaxSize(100);
        cachingProps.setDefaultTtl(3600);
        CachingProperties.CacheRuleConfig rule = new CachingProperties.CacheRuleConfig();
        rule.setPaths(List.of("/api/**"));
        rule.setMethods(List.of("*"));
        rule.setTtl(300);
        cachingProps.setRules(List.of(rule));

        CaffeineProvider cacheProvider = new CaffeineProvider(cachingProps);

        LoggingProperties loggingProps = new LoggingProperties();
        loggingProps.setEnabled(false);

        LoggingAndCachingWebFilter filter = createFilter(loggingProps, cachingProps, cacheProvider);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/data").build());

        AtomicBoolean chainCalled = new AtomicBoolean(false);
        WebFilterChain chain = ex -> {
            chainCalled.set(true);
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(chainCalled.get())
                .as("Upstream chain should be called on cache miss")
                .isTrue();
    }

    @Test
    @DisplayName("should preserve existing x-request-id header")
    void preservesExistingRequestId() {
        LoggingProperties loggingProps = new LoggingProperties();
        loggingProps.setEnabled(true);
        LoggingProperties.RequestConfig config = new LoggingProperties.RequestConfig();
        config.setPaths(List.of("/api/**"));
        config.setMethods(List.of("*"));
        config.setExcludeBody(true);
        loggingProps.setRequests(List.of(config));

        CachingProperties cachingProps = new CachingProperties();
        cachingProps.setEnabled(false);

        LoggingAndCachingWebFilter filter = createFilter(loggingProps, cachingProps, noOpCache);

        String existingId = "my-custom-request-id-123";
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/users")
                        .header("x-request-id", existingId)
                        .build());

        WebFilterChain chain = ex -> {
            String requestId = ex.getRequest().getHeaders().getFirst("x-request-id");
            assertThat(requestId).isEqualTo(existingId);
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();
    }

    @Test
    @DisplayName("should log request and response with body capture enabled")
    void loggingWithBodyCapture() {
        LoggingProperties loggingProps = new LoggingProperties();
        loggingProps.setEnabled(true);
        LoggingProperties.RequestConfig config = new LoggingProperties.RequestConfig();
        config.setPaths(List.of("/api/**"));
        config.setMethods(List.of("GET", "POST"));
        config.setExcludeBody(false);
        loggingProps.setRequests(List.of(config));

        CachingProperties cachingProps = new CachingProperties();
        cachingProps.setEnabled(false);

        LoggingAndCachingWebFilter filter = createFilter(loggingProps, cachingProps, noOpCache);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/data").build());

        AtomicBoolean chainCalled = new AtomicBoolean(false);
        WebFilterChain chain = ex -> {
            chainCalled.set(true);
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(chainCalled.get()).isTrue();
    }

    @Test
    @DisplayName("should log response without body when excludeBody is true")
    void loggingWithExcludeBody() {
        LoggingProperties loggingProps = new LoggingProperties();
        loggingProps.setEnabled(true);
        LoggingProperties.RequestConfig config = new LoggingProperties.RequestConfig();
        config.setPaths(List.of("/**"));
        config.setMethods(List.of("*"));
        config.setExcludeBody(true);
        loggingProps.setRequests(List.of(config));

        CachingProperties cachingProps = new CachingProperties();
        cachingProps.setEnabled(false);

        LoggingAndCachingWebFilter filter = createFilter(loggingProps, cachingProps, noOpCache);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/data/submit").build());

        AtomicBoolean chainCalled = new AtomicBoolean(false);
        WebFilterChain chain = ex -> {
            chainCalled.set(true);
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(chainCalled.get()).isTrue();
    }

    @Test
    @DisplayName("should handle logging + caching together on cache miss")
    void loggingAndCachingTogetherOnMiss() {
        LoggingProperties loggingProps = new LoggingProperties();
        loggingProps.setEnabled(true);
        LoggingProperties.RequestConfig logConfig = new LoggingProperties.RequestConfig();
        logConfig.setPaths(List.of("/api/**"));
        logConfig.setMethods(List.of("*"));
        logConfig.setExcludeBody(false);
        loggingProps.setRequests(List.of(logConfig));

        CachingProperties cachingProps = new CachingProperties();
        cachingProps.setEnabled(true);
        cachingProps.setMaxSize(100);
        cachingProps.setDefaultTtl(3600);
        CachingProperties.CacheRuleConfig rule = new CachingProperties.CacheRuleConfig();
        rule.setPaths(List.of("/api/**"));
        rule.setMethods(List.of("*"));
        rule.setTtl(300);
        cachingProps.setRules(List.of(rule));

        CaffeineProvider cacheProvider = new CaffeineProvider(cachingProps);

        LoggingAndCachingWebFilter filter = createFilter(loggingProps, cachingProps, cacheProvider);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/combined").build());

        AtomicBoolean chainCalled = new AtomicBoolean(false);
        WebFilterChain chain = ex -> {
            chainCalled.set(true);
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(chainCalled.get()).isTrue();
    }

    @Test
    @DisplayName("should handle only logging (no caching) with body capture")
    void onlyLoggingWithBodyCapture() {
        LoggingProperties loggingProps = new LoggingProperties();
        loggingProps.setEnabled(true);
        LoggingProperties.RequestConfig logConfig = new LoggingProperties.RequestConfig();
        logConfig.setPaths(List.of("/data/**"));
        logConfig.setMethods(List.of("POST"));
        logConfig.setExcludeBody(false);
        loggingProps.setRequests(List.of(logConfig));

        CachingProperties cachingProps = new CachingProperties();
        cachingProps.setEnabled(false);

        LoggingAndCachingWebFilter filter = createFilter(loggingProps, cachingProps, noOpCache);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/data/upload").build());

        AtomicBoolean chainCalled = new AtomicBoolean(false);
        WebFilterChain chain = ex -> {
            chainCalled.set(true);
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(chainCalled.get()).isTrue();
    }
}
