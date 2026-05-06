package com.github.ifrugal.gateway.core.filter;

import com.github.ifrugal.gateway.core.cache.CacheProvider;
import com.github.ifrugal.gateway.core.config.CachingProperties;
import com.github.ifrugal.gateway.core.config.LoggingProperties;
import com.github.ifrugal.gateway.core.filter.utils.RequestMatcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
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
    private final List<PathPattern> pathPatternsToIgnore;

    private static final PathPatternParser patternParser = new PathPatternParser();

    /**
     * Default paths to skip for logging/caching (health checks, swagger docs).
     */
    private static final List<String> DEFAULT_IGNORE_PATHS = List.of(
            "/actuator/health",
            "/actuator/health/ping",
            "/swagger-ui/**",
            "/v3/api-docs/**",
            "/swagger-resources/**"
    );

    public LoggingAndCachingWebFilter(
            LoggingProperties loggingProperties,
            CachingProperties cachingProperties,
            CacheProvider cacheProvider) {
        this(loggingProperties, cachingProperties, cacheProvider, null);
    }

    public LoggingAndCachingWebFilter(
            LoggingProperties loggingProperties,
            CachingProperties cachingProperties,
            CacheProvider cacheProvider,
            List<String> ignorePaths) {
        this.loggingProperties = loggingProperties;
        this.cachingProperties = cachingProperties;
        this.cacheProvider = cacheProvider;

        List<String> paths = (ignorePaths != null && !ignorePaths.isEmpty()) ? ignorePaths : DEFAULT_IGNORE_PATHS;
        this.pathPatternsToIgnore = paths.stream()
                .map(patternParser::parse)
                .toList();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(final ServerWebExchange exchange, final WebFilterChain chain) {
        // Skip ignored paths
        boolean shouldIgnore = pathPatternsToIgnore.stream()
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
                            // Short-circuit: serve directly from cache without calling upstream
                            log.info("Cache HIT for key: {}. Serving from cache.", cacheKey);
                            return serveCachedResponse(originalExchange, cachedResponseOpt.get());
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

    /**
     * Generates a deterministic cache key from the request method, path, and query string.
     * Query parameters are sorted alphabetically to ensure consistent key generation
     * regardless of parameter order in the URL.
     *
     * @param request The incoming HTTP request
     * @return A cache key in the format "METHOD:path?sorted-query-params"
     */
    String generateCacheKey(ServerHttpRequest request) {
        String path = request.getPath().value();
        String query = request.getURI().getRawQuery();

        StringBuilder key = new StringBuilder();
        key.append(request.getMethod().name()).append(":").append(path);

        // Sort query parameters for deterministic key generation
        if (query != null && !query.isEmpty()) {
            String[] params = query.split("&");
            Arrays.sort(params);
            key.append("?").append(String.join("&", params));
        }

        return key.toString();
    }

    /**
     * Serve a cached response directly without calling upstream services.
     * This short-circuits the filter chain entirely for cache hits, avoiding
     * unnecessary upstream requests.
     *
     * @param exchange The server web exchange
     * @param cachedBody The cached response body
     * @return Mono that completes when the cached response is written
     */
    private Mono<Void> serveCachedResponse(ServerWebExchange exchange, String cachedBody) {
        ServerHttpResponse response = exchange.getResponse();

        response.setStatusCode(HttpStatus.OK);

        if (!response.getHeaders().containsKey("Content-Type")) {
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        }

        response.getHeaders().add("X-Cache", "HIT");

        byte[] bodyBytes = cachedBody.getBytes();
        response.getHeaders().setContentLength(bodyBytes.length);

        return response.writeWith(
                Mono.just(response.bufferFactory().wrap(bodyBytes))
        );
    }
}
