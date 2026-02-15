package com.github.ifrugal.gateway.core.filter;

import com.github.ifrugal.gateway.core.cache.CacheProvider;
import com.github.ifrugal.gateway.core.config.CachingProperties;
import com.github.ifrugal.gateway.core.config.LoggingProperties;
import com.github.ifrugal.gateway.core.filter.utils.RequestMatcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * WebFilter that provides request/response logging and response caching.
 * Runs at highest precedence to capture all requests.
 */
@Slf4j
public class LoggingAndCachingWebFilter implements WebFilter, Ordered {

    private final LoggingProperties loggingProperties;
    private final CachingProperties cachingProperties;
    private final CacheProvider cacheProvider;

    private static final PathPatternParser patternParser = new PathPatternParser();
    private static final List<PathPattern> PATH_PATTERNS_TO_IGNORE = List.of(
            patternParser.parse("/actuator/health"),
            patternParser.parse("/actuator/health/ping"),
            patternParser.parse("/swagger-ui/**"),
            patternParser.parse("/v3/api-docs/**"),
            patternParser.parse("/swagger-resources/**")
    );

    public LoggingAndCachingWebFilter(
            LoggingProperties loggingProperties,
            CachingProperties cachingProperties,
            CacheProvider cacheProvider) {
        this.loggingProperties = loggingProperties;
        this.cachingProperties = cachingProperties;
        this.cacheProvider = cacheProvider;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(final ServerWebExchange exchange, final WebFilterChain chain) {
        // Skip ignored paths
        boolean shouldIgnore = PATH_PATTERNS_TO_IGNORE.stream()
                .anyMatch(pattern -> pattern.matches(exchange.getRequest().getPath()));
        if (shouldIgnore) {
            return chain.filter(exchange);
        }

        final Instant startTime = Instant.now();
        ServerHttpRequest request = exchange.getRequest();
        final ServerWebExchange originalExchange;

        // Add request ID if not present
        final String requestId = request.getHeaders().getFirst("x-request-id");
        if (requestId == null) {
            final String newRequestId = UUID.randomUUID().toString();
            request = request.mutate().header("x-request-id", newRequestId).build();
            originalExchange = exchange.mutate().request(request).build();
        } else {
            originalExchange = exchange;
        }

        final ServerHttpRequest finalRequest = originalExchange.getRequest();

        // Determine if we should log this request
        final Optional<LoggingProperties.RequestConfig> logConfigOpt =
                RequestMatcher.findMatchingLogConfig(originalExchange, loggingProperties);

        // Determine if we should cache this request/response
        final Optional<RequestMatcher.CacheRule> cacheRuleOpt =
                RequestMatcher.findMatchingCacheRule(originalExchange, cachingProperties);

        // If neither logging nor caching is needed, proceed with normal chain
        if (logConfigOpt.isEmpty() && cacheRuleOpt.isEmpty()) {
            return chain.filter(originalExchange);
        }

        // If caching is needed
        if (cacheRuleOpt.isPresent()) {
            final String cacheKey = generateCacheKey(finalRequest);
            log.debug("Checking cache for key: {}", cacheKey);

            return cacheProvider.get(cacheKey)
                    .flatMap(cachedResponseOpt -> {
                        if (cachedResponseOpt.isPresent()) {
                            log.info("Cache HIT for key: {}. Serving from cache.", cacheKey);
                            return chain.filter(
                                    exchange.mutate()
                                            .response(new CachedResponseDecorator(exchange.getResponse(), cachedResponseOpt.get()))
                                            .build()
                            );
                        } else {
                            log.debug("Cache MISS for key: {}. Fetching from upstream.", cacheKey);
                            return processRequestWithCapture(
                                    originalExchange, chain, logConfigOpt, cacheRuleOpt, cacheKey, startTime);
                        }
                    });
        }

        // If only logging is needed
        return processRequestWithCapture(originalExchange, chain, logConfigOpt, Optional.empty(), null, startTime);
    }

    private Mono<Void> processRequestWithCapture(
            final ServerWebExchange exchange,
            final WebFilterChain chain,
            final Optional<LoggingProperties.RequestConfig> logConfigOpt,
            final Optional<RequestMatcher.CacheRule> cacheRuleOpt,
            final String cacheKey,
            final Instant startTime) {

        final boolean captureBody = logConfigOpt
                .map(config -> !config.isExcludeBody())
                .orElse(false) || cacheRuleOpt.isPresent();

        if (captureBody) {
            final BodyCaptureExchange bodyCaptureExchange = new BodyCaptureExchange(exchange);

            return bodyCaptureExchange.getRequest().getFullBodyAsync()
                    .doOnNext(requestBody -> {
                        exchange.getAttributes().put("REQUEST_BODY", requestBody);
                        if (logConfigOpt.isPresent()) {
                            logRequest(exchange, logConfigOpt.get(), requestBody);
                        }
                    })
                    .then(chain.filter(bodyCaptureExchange))
                    .doFinally(signalType -> {
                        if (logConfigOpt.isPresent()) {
                            logResponse(exchange, bodyCaptureExchange, logConfigOpt.get(),
                                    Duration.between(startTime, Instant.now()).toMillis());
                        }

                        if (cacheRuleOpt.isPresent() && cacheKey != null) {
                            final String responseBody = bodyCaptureExchange.getResponse().getFullBody();
                            if (responseBody != null && !responseBody.isBlank() &&
                                    exchange.getResponse().getStatusCode() != null &&
                                    exchange.getResponse().getStatusCode().is2xxSuccessful()) {

                                final long ttl = cacheRuleOpt.get().getTtlSeconds();
                                log.debug("Storing response in cache for key: {} with TTL: {} seconds", cacheKey, ttl);

                                cacheProvider.put(cacheKey, responseBody, ttl)
                                        .subscribe(null, error ->
                                                log.error("Error caching response: {}", error.getMessage()));
                            }
                        }
                    });
        } else {
            return chain.filter(exchange)
                    .doFinally(signalType -> {
                        if (logConfigOpt.isPresent()) {
                            logResponseWithoutBody(exchange, logConfigOpt.get(),
                                    Duration.between(startTime, Instant.now()).toMillis());
                        }
                    });
        }
    }

    private void logRequest(ServerWebExchange exchange, LoggingProperties.RequestConfig logConfig, String requestBody) {
        final ServerHttpRequest request = exchange.getRequest();
        final Map<String, Object> requestInfo = new LinkedHashMap<>();

        requestInfo.put("timestamp", Instant.now().toString());
        requestInfo.put("requestId", request.getHeaders().getFirst("x-request-id"));
        requestInfo.put("method", request.getMethod().toString());
        requestInfo.put("path", request.getPath().value());
        requestInfo.put("queryParams", request.getQueryParams());

        // Remove sensitive headers
        final Map<String, List<String>> safeHeaders = new HashMap<>(request.getHeaders());
        safeHeaders.remove("Authorization");
        safeHeaders.remove("authorization");
        safeHeaders.remove("Cookie");
        safeHeaders.remove("cookie");
        requestInfo.put("headers", safeHeaders);

        if (!logConfig.isExcludeBody() && requestBody != null && !requestBody.isBlank()) {
            requestInfo.put("body", requestBody);
        }

        log.info("Request: {}", requestInfo);
    }

    private void logResponse(ServerWebExchange exchange, BodyCaptureExchange bodyCaptureExchange,
                             LoggingProperties.RequestConfig logConfig, long durationMs) {
        final ServerHttpResponse response = exchange.getResponse();
        final Map<String, Object> responseInfo = new LinkedHashMap<>();

        responseInfo.put("timestamp", Instant.now().toString());
        responseInfo.put("requestId", exchange.getRequest().getHeaders().getFirst("x-request-id"));
        responseInfo.put("status", response.getStatusCode() != null ?
                response.getStatusCode().value() : HttpStatus.INTERNAL_SERVER_ERROR.value());
        responseInfo.put("headers", response.getHeaders());
        responseInfo.put("durationMs", durationMs);

        if (!logConfig.isExcludeBody()) {
            final String responseBody = bodyCaptureExchange.getResponse().getFullBody();
            if (responseBody != null && !responseBody.isBlank()) {
                responseInfo.put("body", responseBody);
            }
        }

        log.info("Response: {}", responseInfo);
    }

    private void logResponseWithoutBody(ServerWebExchange exchange, LoggingProperties.RequestConfig logConfig,
                                        long durationMs) {
        final ServerHttpResponse response = exchange.getResponse();
        final Map<String, Object> responseInfo = new LinkedHashMap<>();

        responseInfo.put("timestamp", Instant.now().toString());
        responseInfo.put("requestId", exchange.getRequest().getHeaders().getFirst("x-request-id"));
        responseInfo.put("status", response.getStatusCode() != null ?
                response.getStatusCode().value() : HttpStatus.INTERNAL_SERVER_ERROR.value());
        responseInfo.put("headers", response.getHeaders());
        responseInfo.put("durationMs", durationMs);

        log.info("Response: {}", responseInfo);
    }

    private String generateCacheKey(ServerHttpRequest request) {
        return request.getMethod().name() + ":" + request.getURI();
    }

    /**
     * Response decorator that serves cached content.
     */
    private static class CachedResponseDecorator implements ServerHttpResponse {
        private final ServerHttpResponse delegate;
        private final String cachedBody;
        private boolean committed = false;

        public CachedResponseDecorator(ServerHttpResponse delegate, String cachedBody) {
            this.delegate = delegate;
            this.cachedBody = cachedBody;

            if (delegate.getStatusCode() == null) {
                delegate.setStatusCode(HttpStatus.OK);
            }

            if (!delegate.getHeaders().containsKey("Content-Type")) {
                delegate.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            }

            delegate.getHeaders().setContentLength(cachedBody.getBytes().length);
        }

        @Override
        public Mono<Void> writeWith(org.reactivestreams.Publisher<? extends org.springframework.core.io.buffer.DataBuffer> body) {
            if (!this.committed) {
                this.committed = true;
                return this.delegate.writeWith(
                        Mono.just(this.delegate.bufferFactory().wrap(this.cachedBody.getBytes()))
                );
            }
            return Mono.empty();
        }

        @Override
        public Mono<Void> writeAndFlushWith(org.reactivestreams.Publisher<? extends org.reactivestreams.Publisher<? extends org.springframework.core.io.buffer.DataBuffer>> body) {
            return this.writeWith(Mono.empty());
        }

        @Override
        public Mono<Void> setComplete() {
            return this.writeWith(Mono.empty());
        }

        @Override
        public boolean isCommitted() {
            return this.committed || this.delegate.isCommitted();
        }

        @Override
        public HttpStatusCode getStatusCode() {
            return this.delegate.getStatusCode();
        }

        @Override
        public boolean setStatusCode(HttpStatusCode statusCode) {
            return this.delegate.setStatusCode(statusCode);
        }

        @Override
        public MultiValueMap<String, ResponseCookie> getCookies() {
            return this.delegate.getCookies();
        }

        @Override
        public void addCookie(ResponseCookie cookie) {
            this.delegate.addCookie(cookie);
        }

        @Override
        public org.springframework.http.HttpHeaders getHeaders() {
            return this.delegate.getHeaders();
        }

        @Override
        public org.springframework.core.io.buffer.DataBufferFactory bufferFactory() {
            return this.delegate.bufferFactory();
        }

        @Override
        public void beforeCommit(java.util.function.Supplier<? extends Mono<Void>> action) {
            this.delegate.beforeCommit(action);
        }
    }
}
