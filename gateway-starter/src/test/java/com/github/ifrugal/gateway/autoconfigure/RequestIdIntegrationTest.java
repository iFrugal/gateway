package com.github.ifrugal.gateway.autoconfigure;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.github.ifrugal.gateway.core.filter.LoggingAndCachingWebFilter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.HttpHandlerDecoratorFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-context proof of request-id handling: a real gateway on a random port proxying to a
 * local Netty upstream, with Spring Security, CORS, actuator and the WebFlux error handler.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = RequestIdIntegrationTest.TestApplication.class,
        properties = {
                "spring.cloud.consul.enabled=false",
                "spring.cloud.consul.discovery.enabled=false",
                "gateway.security.enabled=true",
                "gateway.security.guest-allowed-paths=/proxied/**,/broken/**,/mdc-probe/**,/boom/**",
                "gateway.cors.allowed-origins=http://app.example.test",
                "gateway.cors.exposed-headers=X-Custom-Header,X-REQUEST-ID",
                "gateway.logging.requests[0].paths=/proxied/**",
                "gateway.logging.requests[0].methods=*",
                "spring.cloud.gateway.server.webflux.httpclient.connect-timeout=2000"
        })
@DisplayName("Request id (integration)")
class RequestIdIntegrationTest {

    private static final String HEADER = "x-request-id";
    private static final String UPSTREAM_OWN_ID = "upstream-own-id-999";
    private static final org.slf4j.Logger PROBE_LOG = LoggerFactory.getLogger("requestid.probe");
    private static final org.slf4j.Logger OUTER_LOG = LoggerFactory.getLogger("requestid.outer");

    private static DisposableServer upstream;

    @LocalServerPort
    private int port;

    private WebTestClient client;
    private CapturingAppender appender;

    /**
     * Local upstream: echoes every received request-id value in the body and sends its own
     * request-id response header, which the gateway must not leak to the client.
     */
    private static synchronized DisposableServer upstream() {
        if (upstream == null) {
            upstream = HttpServer.create()
                    .host("127.0.0.1")
                    .port(0)
                    .handle((request, response) -> {
                        String received = request.requestHeaders().getAll(HEADER).stream()
                                .map(value -> "\"" + value + "\"")
                                .collect(Collectors.joining(","));
                        return response
                                .header(HEADER, UPSTREAM_OWN_ID)
                                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                                .sendString(Mono.just("{\"received\":[" + received + "]}"));
                    })
                    .bindNow();
        }
        return upstream;
    }

    /**
     * All route entries live in one property source: Boot binds an indexed list from a single source.
     */
    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry registry) {
        String routes = "spring.cloud.gateway.server.webflux.routes";
        registry.add(routes + "[0].id", () -> "proxied");
        registry.add(routes + "[0].uri", () -> "http://127.0.0.1:" + upstream().port());
        registry.add(routes + "[0].predicates[0]", () -> "Path=/proxied/**,/mdc-probe/**");
        registry.add(routes + "[1].id", () -> "broken");
        registry.add(routes + "[1].uri", () -> "http://127.0.0.1:1");
        registry.add(routes + "[1].predicates[0]", () -> "Path=/broken/**");
    }

    @AfterAll
    static void stopUpstream() {
        if (upstream != null) {
            upstream.disposeNow();
        }
    }

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(15))
                .build();
        appender = new CapturingAppender();
        appender.start();
        for (String name : List.of("requestid.probe", "requestid.outer", LoggingAndCachingWebFilter.class.getName())) {
            ((Logger) LoggerFactory.getLogger(name)).addAppender(appender);
        }
    }

    @AfterEach
    void tearDown() {
        for (String name : List.of("requestid.probe", "requestid.outer", LoggingAndCachingWebFilter.class.getName())) {
            ((Logger) LoggerFactory.getLogger(name)).detachAppender(appender);
        }
        appender.stop();
    }

    // ---------------------------------------------------------------- echo

    @Test
    @DisplayName("should forward a valid id once and echo it on a proxied response, overriding the upstream header")
    void echoOnProxiedRoute() {
        client.get().uri("/proxied/hello")
                .header(HEADER, "proxied-request-001")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valuesMatch(HEADER, "proxied-request-001")
                .expectBody()
                .jsonPath("$.received.length()").isEqualTo(1)
                .jsonPath("$.received[0]").isEqualTo("proxied-request-001");
    }

    @Test
    @DisplayName("should replace invalid and multi-valued ids with one UUID forwarded and echoed")
    void replacesInvalidIdEndToEnd() {
        EntityExchangeResult<byte[]> result = client.get().uri("/proxied/hello")
                .header(HEADER, "bad id\twith spaces", "second-valid-id")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.received.length()").isEqualTo(1)
                .returnResult();

        List<String> echoed = result.getResponseHeaders().get(HEADER);
        assertThat(echoed).hasSize(1);
        assertThat(UUID.fromString(echoed.get(0)).toString()).isEqualTo(echoed.get(0));
        assertThat(new String(result.getResponseBody())).contains("\"" + echoed.get(0) + "\"");
    }

    @Test
    @DisplayName("should echo the id on a 401 written by Spring Security")
    void echoOnSecurity401() {
        client.get().uri("/secured/resource")
                .header(HEADER, "unauthenticated-1")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valuesMatch(HEADER, "unauthenticated-1");
    }

    @Test
    @DisplayName("should echo the id and expose it via CORS on a cross-origin request")
    void echoAndExposeOnCorsRequest() {
        EntityExchangeResult<byte[]> result = client.get().uri("/proxied/cors")
                .header(HttpHeaders.ORIGIN, "http://app.example.test")
                .header(HEADER, "cors-request-0001")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valuesMatch(HEADER, "cors-request-0001")
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://app.example.test")
                .expectBody().returnResult();

        List<String> exposed = result.getResponseHeaders().getAccessControlExposeHeaders();
        assertThat(exposed).containsExactly("X-Custom-Header", "X-REQUEST-ID");
    }

    @Test
    @DisplayName("should assign and echo an id on an ignored path (/actuator/health)")
    void echoOnIgnoredHealthPath() {
        EntityExchangeResult<byte[]> result = client.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody().returnResult();

        String echoed = result.getResponseHeaders().getFirst(HEADER);
        assertThat(echoed).isNotNull();
        assertThat(UUID.fromString(echoed).toString()).isEqualTo(echoed);
    }

    // ---------------------------------------------------------------- error bodies

    @Test
    @DisplayName("should put the echoed id in the error body of an unreachable upstream (5xx)")
    void errorBodyRequestIdOnUnreachableUpstream() {
        client.get().uri("/broken/anything")
                .header(HEADER, "broken-route-0001")
                .exchange()
                .expectStatus().is5xxServerError()
                .expectHeader().valuesMatch(HEADER, "broken-route-0001")
                .expectBody()
                .jsonPath("$.requestId").isEqualTo("broken-route-0001");
    }

    @Test
    @DisplayName("should put the echoed (generated) id in the error body of a ResponseStatusException")
    void errorBodyRequestIdOnResponseStatusException() {
        EntityExchangeResult<byte[]> result = client.get().uri("/boom")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody().returnResult();

        String echoed = result.getResponseHeaders().getFirst(HEADER);
        assertThat(echoed).isNotNull();
        assertThat(new String(result.getResponseBody())).contains("\"requestId\":\"" + echoed + "\"");
    }

    // ---------------------------------------------------------------- MDC

    @Test
    @DisplayName("should expose MDC requestId in a later WebFilter body and after thread hops")
    void mdcInLaterFilterBodyAndAfterThreadHops() {
        client.get().uri("/mdc-probe/one")
                .header(HEADER, "mdc-probe-000001")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valuesMatch(HEADER, "mdc-probe-000001");

        ILoggingEvent body = appender.awaitEvent(e -> e.getFormattedMessage().equals("probe-body mdc-probe-000001"));
        ILoggingEvent afterDelay = appender.awaitEvent(
                e -> e.getFormattedMessage().equals("probe-after-delay mdc-probe-000001"));
        ILoggingEvent afterPublishOn = appender.awaitEvent(
                e -> e.getFormattedMessage().equals("probe-after-publishOn mdc-probe-000001"));
        ILoggingEvent afterUpstream = appender.awaitEvent(
                e -> e.getFormattedMessage().equals("probe-after-upstream mdc-probe-000001"));

        // (a) filter body, executed at subscription time inside DefaultWebFilterChain's Mono.defer
        assertThat(body.getMDCPropertyMap()).containsEntry("requestId", "mdc-probe-000001");
        // (b) async continuations after real thread hops
        assertThat(afterDelay.getThreadName()).startsWith("parallel-");
        assertThat(afterDelay.getMDCPropertyMap()).containsEntry("requestId", "mdc-probe-000001");
        assertThat(afterPublishOn.getThreadName()).startsWith("boundedElastic-");
        assertThat(afterPublishOn.getMDCPropertyMap()).containsEntry("requestId", "mdc-probe-000001");
        // continuation after the proxied upstream call completed on a Netty thread
        assertThat(afterUpstream.getMDCPropertyMap()).containsEntry("requestId", "mdc-probe-000001");
    }

    @Test
    @DisplayName("should carry MDC requestId on the toolkit's own request/response log lines")
    void mdcOnToolkitLogLines() {
        client.get().uri("/proxied/logged")
                .header(HEADER, "logged-request-01")
                .exchange()
                .expectStatus().isOk();

        ILoggingEvent request = appender.awaitEvent(e -> e.getFormattedMessage().startsWith("Request: ")
                && e.getFormattedMessage().contains("logged-request-01"));
        ILoggingEvent response = appender.awaitEvent(e -> e.getFormattedMessage().startsWith("Response: ")
                && e.getFormattedMessage().contains("logged-request-01"));

        assertThat(request.getMDCPropertyMap()).containsEntry("requestId", "logged-request-01");
        assertThat(response.getMDCPropertyMap()).containsEntry("requestId", "logged-request-01");
    }

    @Test
    @DisplayName("should clear MDC after each request so ids never leak to later requests on shared threads")
    void mdcClearedAfterRequest() {
        int requests = 6;
        for (int i = 0; i < requests; i++) {
            String id = "leak-check-00000" + i;
            client.get().uri("/mdc-probe/leak-" + i)
                    .header(HEADER, id)
                    .exchange()
                    .expectStatus().isOk()
                    .expectHeader().valuesMatch(HEADER, id);
            appender.awaitEvent(e -> e.getFormattedMessage().equals("probe-after-upstream " + id));
        }

        List<ILoggingEvent> outer = appender.events(e -> e.getFormattedMessage().startsWith("outer-before /mdc-probe/leak-"));
        assertThat(outer).hasSize(requests);
        // The outer decorator runs on Netty event-loop threads before the gateway filter, so any
        // leaked MDC from an earlier request on the same thread would show up here.
        assertThat(outer).allSatisfy(e -> assertThat(e.getMDCPropertyMap()).doesNotContainKey("requestId"));
        // Sequential keep-alive requests reuse an event-loop thread, so the check is meaningful.
        assertThat(outer.stream().map(ILoggingEvent::getThreadName).distinct().count()).isLessThan(requests);

        for (int i = 0; i < requests; i++) {
            String id = "leak-check-00000" + i;
            assertThat(appender.events(e -> e.getFormattedMessage().endsWith(" " + id)))
                    .hasSize(4)
                    .allSatisfy(e -> assertThat(e.getMDCPropertyMap()).containsEntry("requestId", id));
        }
        // The test thread itself never had the key.
        assertThat(MDC.get("requestId")).isNull();
    }

    // ---------------------------------------------------------------- test application

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {

        @Bean
        MdcProbeWebFilter mdcProbeWebFilter() {
            return new MdcProbeWebFilter();
        }

        @Bean
        HttpHandlerDecoratorFactory outerLoggingDecorator() {
            return handler -> (request, response) -> {
                OUTER_LOG.info("outer-before {}", request.getPath().value());
                return handler.handle(request, response);
            };
        }

        @Bean
        RouterFunction<ServerResponse> boomRoute() {
            return RouterFunctions.route()
                    .GET("/boom", request -> Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "boom")))
                    .build();
        }
    }

    /**
     * A filter that runs after Spring Security and the gateway's own filter and logs from its
     * body, after a parallel-scheduler delay, after a boundedElastic publishOn and after the
     * proxied upstream call. Each line also carries the request id from the exchange attribute
     * so the test can correlate it independently of the MDC.
     */
    static class MdcProbeWebFilter implements WebFilter, Ordered {

        @Override
        public int getOrder() {
            return 0;
        }

        @Override
        public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
            if (!exchange.getRequest().getPath().value().startsWith("/mdc-probe/")) {
                return chain.filter(exchange);
            }
            String id = exchange.getAttribute(LoggingAndCachingWebFilter.REQUEST_ID_ATTRIBUTE);
            PROBE_LOG.info("probe-body {}", id);
            return Mono.delay(Duration.ofMillis(10))
                    .doOnNext(tick -> PROBE_LOG.info("probe-after-delay {}", id))
                    .publishOn(Schedulers.boundedElastic())
                    .then(Mono.fromRunnable(() -> PROBE_LOG.info("probe-after-publishOn {}", id)))
                    .then(chain.filter(exchange))
                    .doOnSuccess(done -> PROBE_LOG.info("probe-after-upstream {}", id));
        }
    }

    /**
     * Appender that snapshots each event's MDC on the logging thread at append time.
     */
    static final class CapturingAppender extends AppenderBase<ILoggingEvent> {

        private final List<ILoggingEvent> captured = new CopyOnWriteArrayList<>();

        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            captured.add(event);
        }

        List<ILoggingEvent> events(Predicate<ILoggingEvent> predicate) {
            return captured.stream().filter(predicate).toList();
        }

        ILoggingEvent awaitEvent(Predicate<ILoggingEvent> predicate) {
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline) {
                List<ILoggingEvent> matches = events(predicate);
                if (!matches.isEmpty()) {
                    return matches.get(0);
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            throw new AssertionError("No matching log event captured; got: "
                    + captured.stream().map(e -> e.getFormattedMessage() + " " + e.getMDCPropertyMap()).toList());
        }
    }
}
