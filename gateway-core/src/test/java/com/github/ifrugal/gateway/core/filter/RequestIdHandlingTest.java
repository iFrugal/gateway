package com.github.ifrugal.gateway.core.filter;

import com.github.ifrugal.gateway.core.cache.CaffeineProvider;
import com.github.ifrugal.gateway.core.cache.NoOpCacheProvider;
import com.github.ifrugal.gateway.core.config.CachingProperties;
import com.github.ifrugal.gateway.core.config.LoggingProperties;
import com.github.ifrugal.gateway.core.config.RequestIdProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LoggingAndCachingWebFilter request id")
class RequestIdHandlingTest {

    private static final String HEADER = "x-request-id";

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private static LoggingAndCachingWebFilter defaultFilter() {
        return new LoggingAndCachingWebFilter(new LoggingProperties(), new CachingProperties(), new NoOpCacheProvider());
    }

    /**
     * Runs the filter with a chain that records the forwarded exchange and commits the response.
     */
    private static ServerWebExchange run(LoggingAndCachingWebFilter filter, MockServerWebExchange exchange) {
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        WebFilterChain chain = ex -> {
            forwarded.set(ex);
            return ex.getResponse().setComplete();
        };
        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
        assertThat(forwarded.get()).as("chain must be called").isNotNull();
        return forwarded.get();
    }

    private static void assertIsUuid(String value) {
        assertThat(value).isNotNull();
        assertThat(UUID.fromString(value).toString()).isEqualTo(value);
    }

    @Nested
    @DisplayName("Validation")
    class Validation {

        @ParameterizedTest(name = "keeps valid id \"{0}\"")
        @ValueSource(strings = {
                "abcdefgh",
                "my-custom-request-id-123",
                "A.b_c-D.e_f-0123456789",
                "0123456789012345678901234567890123456789012345678901234567890123",
                "3f2b8c3e-6a1d-4c4b-9a55-2d8f0a6b1c7e"
        })
        void keepsValidId(String inbound) {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/users").header(HEADER, inbound).build());

            ServerWebExchange forwarded = run(defaultFilter(), exchange);

            assertThat(forwarded.getRequest().getHeaders().get(HEADER)).containsExactly(inbound);
            assertThat(exchange.getResponse().getHeaders().get(HEADER)).containsExactly(inbound);
            assertThat((String) exchange.getAttribute(LoggingAndCachingWebFilter.REQUEST_ID_ATTRIBUTE))
                    .isEqualTo(inbound);
        }

        @ParameterizedTest(name = "replaces invalid id \"{0}\"")
        @NullAndEmptySource
        @ValueSource(strings = {
                "        ",
                "short",
                "1234567",
                "01234567890123456789012345678901234567890123456789012345678901234",
                "abc def ghi",
                "abcdefgh\r\nX-Injected: 1",
                "abcdefgh\nfake-log-line",
                "abcdefgh;rm -rf",
                "<script>alert(1)</script>",
                "abcdefgh%0d%0a",
                "ünïcödé-ïd-123",
                "abcdefgh,ijklmnop"
        })
        void replacesInvalidId(String inbound) {
            MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get("/api/users");
            if (inbound != null) {
                builder.header(HEADER, inbound);
            }
            MockServerWebExchange exchange = MockServerWebExchange.from(builder.build());

            ServerWebExchange forwarded = run(defaultFilter(), exchange);

            List<String> forwardedValues = forwarded.getRequest().getHeaders().get(HEADER);
            assertThat(forwardedValues).hasSize(1);
            assertIsUuid(forwardedValues.get(0));
            assertThat(exchange.getResponse().getHeaders().get(HEADER)).containsExactlyElementsOf(forwardedValues);
            assertThat((String) exchange.getAttribute(LoggingAndCachingWebFilter.REQUEST_ID_ATTRIBUTE))
                    .isEqualTo(forwardedValues.get(0));
        }

        @Test
        @DisplayName("should generate a different UUID per request")
        void generatesDistinctIds() {
            ServerWebExchange first = run(defaultFilter(),
                    MockServerWebExchange.from(MockServerHttpRequest.get("/a").build()));
            ServerWebExchange second = run(defaultFilter(),
                    MockServerWebExchange.from(MockServerHttpRequest.get("/a").build()));

            assertThat(first.getRequest().getHeaders().getFirst(HEADER))
                    .isNotEqualTo(second.getRequest().getHeaders().getFirst(HEADER));
        }

        @Test
        @DisplayName("should use the first of multiple values and forward exactly one value")
        void multipleValuesFirstValid() {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/users")
                            .header(HEADER, "first-valid-id", "second-valid-id")
                            .build());

            ServerWebExchange forwarded = run(defaultFilter(), exchange);

            assertThat(forwarded.getRequest().getHeaders().get(HEADER)).containsExactly("first-valid-id");
            assertThat(exchange.getResponse().getHeaders().get(HEADER)).containsExactly("first-valid-id");
        }

        @Test
        @DisplayName("should replace all values when the first of multiple values is invalid")
        void multipleValuesFirstInvalid() {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/users")
                            .header(HEADER, "bad id", "second-valid-id")
                            .build());

            ServerWebExchange forwarded = run(defaultFilter(), exchange);

            List<String> values = forwarded.getRequest().getHeaders().get(HEADER);
            assertThat(values).hasSize(1);
            assertIsUuid(values.get(0));
        }

        @Test
        @DisplayName("should match the header name case-insensitively")
        void headerNameCaseInsensitive() {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/users").header("X-Request-ID", "mixed-case-header").build());

            ServerWebExchange forwarded = run(defaultFilter(), exchange);

            assertThat(forwarded.getRequest().getHeaders().get(HEADER)).containsExactly("mixed-case-header");
        }
    }

    @Nested
    @DisplayName("Custom configuration")
    class CustomConfiguration {

        private LoggingAndCachingWebFilter filterWith(RequestIdProperties properties) {
            return new LoggingAndCachingWebFilter(new LoggingProperties(), new CachingProperties(),
                    new NoOpCacheProvider(), null, properties);
        }

        @Test
        @DisplayName("should use a custom header name for reading, forwarding and echoing")
        void customHeaderName() {
            RequestIdProperties properties = new RequestIdProperties();
            properties.setHeaderName("X-Correlation-Id");
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/users")
                            .header("X-Correlation-Id", "correlation-123")
                            .header(HEADER, "ignored-default-header")
                            .build());

            ServerWebExchange forwarded = run(filterWith(properties), exchange);

            assertThat(forwarded.getRequest().getHeaders().get("X-Correlation-Id")).containsExactly("correlation-123");
            assertThat(exchange.getResponse().getHeaders().get("X-Correlation-Id")).containsExactly("correlation-123");
            assertThat(exchange.getResponse().getHeaders().containsHeader(HEADER)).isFalse();
        }

        @Test
        @DisplayName("should validate against a custom pattern")
        void customPattern() {
            RequestIdProperties properties = new RequestIdProperties();
            properties.setPattern("^req-[0-9]{4}$");
            LoggingAndCachingWebFilter filter = filterWith(properties);

            ServerWebExchange kept = run(filter, MockServerWebExchange.from(
                    MockServerHttpRequest.get("/a").header(HEADER, "req-1234").build()));
            ServerWebExchange replaced = run(filter, MockServerWebExchange.from(
                    MockServerHttpRequest.get("/a").header(HEADER, "valid-by-default-pattern").build()));

            assertThat(kept.getRequest().getHeaders().getFirst(HEADER)).isEqualTo("req-1234");
            assertIsUuid(replaced.getRequest().getHeaders().getFirst(HEADER));
        }
    }

    @Nested
    @DisplayName("Echo and context")
    class EchoAndContext {

        @Test
        @DisplayName("should echo the id on a logged, proxied response even if a later filter overwrites it")
        void echoSurvivesOverwrite() {
            LoggingProperties loggingProps = new LoggingProperties();
            LoggingProperties.RequestConfig config = new LoggingProperties.RequestConfig();
            config.setPaths(List.of("/api/**"));
            config.setMethods(List.of("*"));
            loggingProps.setRequests(List.of(config));
            LoggingAndCachingWebFilter filter = new LoggingAndCachingWebFilter(
                    loggingProps, new CachingProperties(), new NoOpCacheProvider());
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/users").header(HEADER, "echo-me-please").build());

            // Simulates the gateway copying an upstream response header over ours.
            WebFilterChain chain = ex -> {
                ex.getResponse().getHeaders().set(HEADER, "upstream-value");
                return ex.getResponse().setComplete();
            };
            StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

            assertThat(exchange.getResponse().getHeaders().get(HEADER)).containsExactly("echo-me-please");
        }

        @Test
        @DisplayName("should echo the id on a cache hit")
        void echoOnCacheHit() {
            CachingProperties cachingProps = new CachingProperties();
            cachingProps.setEnabled(true);
            CachingProperties.CacheRuleConfig rule = new CachingProperties.CacheRuleConfig();
            rule.setPaths(List.of("/api/**"));
            rule.setMethods(List.of("*"));
            rule.setTtl(300);
            cachingProps.setRules(List.of(rule));
            CaffeineProvider cacheProvider = new CaffeineProvider(cachingProps);
            cacheProvider.put("GET:/api/data", "{\"cached\":true}", 300).block();
            LoggingProperties loggingProps = new LoggingProperties();
            loggingProps.setEnabled(false);
            LoggingAndCachingWebFilter filter = new LoggingAndCachingWebFilter(loggingProps, cachingProps, cacheProvider);

            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/data").header(HEADER, "cached-request-1").build());
            WebFilterChain chain = ex -> {
                throw new AssertionError("Chain should not be called on cache hit");
            };
            StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

            HttpHeaders headers = exchange.getResponse().getHeaders();
            assertThat(headers.getFirst("X-Cache")).isEqualTo("HIT");
            assertThat(headers.get(HEADER)).containsExactly("cached-request-1");
        }

        @Test
        @DisplayName("should assign, forward and echo an id on ignored paths")
        void echoOnIgnoredPath() {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/actuator/health").header(HEADER, "x").build());

            ServerWebExchange forwarded = run(defaultFilter(), exchange);

            String id = forwarded.getRequest().getHeaders().getFirst(HEADER);
            assertIsUuid(id);
            assertThat(exchange.getResponse().getHeaders().get(HEADER)).containsExactly(id);
            assertThat((String) exchange.getAttribute(LoggingAndCachingWebFilter.REQUEST_ID_ATTRIBUTE)).isEqualTo(id);
        }

        @Test
        @DisplayName("should write the id into the Reactor Context of the downstream chain")
        void writesReactorContext() {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/users").header(HEADER, "context-id-123").build());
            AtomicReference<String> seen = new AtomicReference<>();
            WebFilterChain chain = ex -> Mono.deferContextual(ctx -> {
                seen.set(ctx.get(LoggingAndCachingWebFilter.REQUEST_ID_CONTEXT_KEY));
                return Mono.empty();
            });

            StepVerifier.create(defaultFilter().filter(exchange, chain)).verifyComplete();

            assertThat(seen.get()).isEqualTo("context-id-123");
        }
    }

    @Nested
    @DisplayName("RequestIdThreadLocalAccessor")
    class Accessor {

        @Test
        @DisplayName("should read, write and reset only its MDC key")
        void bridgesMdcKey() {
            RequestIdThreadLocalAccessor accessor = new RequestIdThreadLocalAccessor("rid");
            MDC.put("other", "untouched");

            assertThat(accessor.key()).isEqualTo(LoggingAndCachingWebFilter.REQUEST_ID_CONTEXT_KEY);
            assertThat(accessor.getValue()).isNull();

            accessor.setValue("abc-12345");
            assertThat(MDC.get("rid")).isEqualTo("abc-12345");
            assertThat(accessor.getValue()).isEqualTo("abc-12345");

            accessor.setValue();
            assertThat(MDC.get("rid")).isNull();

            accessor.setValue("abc-12345");
            accessor.reset();
            assertThat(MDC.get("rid")).isNull();
            assertThat(MDC.get("other")).isEqualTo("untouched");
        }
    }
}
