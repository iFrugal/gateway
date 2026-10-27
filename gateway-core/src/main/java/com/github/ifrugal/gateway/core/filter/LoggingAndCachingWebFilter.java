package com.github.ifrugal.gateway.core.filter;

import com.github.ifrugal.gateway.core.cache.CacheProvider;
import com.github.ifrugal.gateway.core.config.CachingProperties;
import com.github.ifrugal.gateway.core.config.LoggingProperties;
import com.github.ifrugal.gateway.core.config.RequestIdProperties;
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
import reactor.util.context.Context;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * WebFilter that provides request-id handling, request/response logging and response caching.
 * Runs at highest precedence to capture all requests.
 *
 * <p><b>Request id.</b> Every request, including ignored paths and cache hits, gets exactly
 * one request id (see {@link RequestIdProperties}):</p>
 * <ul>
 *   <li>the first inbound value of the configured header is kept only if it fully matches the
 *       configured pattern; otherwise a random UUID is generated;</li>
 *   <li>the forwarded request carries exactly one value of the header (set, never added);</li>
 *   <li>the id is echoed on every response through {@link ServerHttpResponse#beforeCommit},
 *       so it survives upstream header copying, Spring Security rejections and error handling;</li>
 *   <li>the id is stored as the exchange attribute {@link #REQUEST_ID_ATTRIBUTE} and in the
 *       Reactor Context under {@link #REQUEST_ID_CONTEXT_KEY}, which
 *       {@link RequestIdThreadLocalAccessor} bridges to the SLF4J MDC.</li>
 * </ul>
 */
@Slf4j
public class LoggingAndCachingWebFilter implements WebFilter, Ordered {

    /**
     * Exchange attribute holding the final (validated or generated) request id.
     */
    public static final String REQUEST_ID_ATTRIBUTE = LoggingAndCachingWebFilter.class.getName() + ".requestId";

    /**
     * Reactor Context key holding the final request id for the whole downstream filter chain.
     */
    public static final String REQUEST_ID_CONTEXT_KEY = "requestId";

    private final LoggingProperties loggingProperties;
    private final CachingProperties cachingProperties;
    private final CacheProvider cacheProvider;
    private final List<PathPattern> pathPatternsToIgnore;
    private final String requestIdHeader;
    private final Pattern requestIdPattern;

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
        this(loggingProperties, cachingProperties, cacheProvider, ignorePaths, new RequestIdProperties());
    }

    /**
     * @param ignorePaths         paths skipped for logging/caching (defaults when null or empty);
     *                            they still get a request id
     * @param requestIdProperties request-id settings; the pattern is compiled once here
     */
    public LoggingAndCachingWebFilter(
            LoggingProperties loggingProperties,
            CachingProperties cachingProperties,
            CacheProvider cacheProvider,
            List<String> ignorePaths,
            RequestIdProperties requestIdProperties) {
        this.loggingProperties = loggingProperties;
        this.cachingProperties = cachingProperties;
        this.cacheProvider = cacheProvider;
        this.requestIdHeader = requestIdProperties.getHeaderName();
        this.requestIdPattern = Pattern.compile(requestIdProperties.getPattern());

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
        final String requestId = resolveRequestId(exchange.getRequest());

        // Forward exactly one value downstream (set replaces every inbound value).
        final ServerWebExchange requestIdExchange = exchange.mutate()
                .request(builder -> builder.headers(headers -> headers.set(requestIdHeader, requestId)))
                .build();
        // Attributes are shared between the original and the mutated exchange.
        exchange.getAttributes().put(REQUEST_ID_ATTRIBUTE, requestId);
        echoRequestId(exchange.getResponse(), requestId);

        // defer() so that this filter's own work runs inside the context written below; with
        // automatic context propagation enabled, contextWrite also sets the MDC around the
        // subscription and every signal, and restores it afterwards.
        return Mono.defer(() -> filterWithRequestId(requestIdExchange, chain))
                .contextWrite(Context.of(REQUEST_ID_CONTEXT_KEY, requestId));
    }

    /**
     * Return the first inbound request-id value when it fully matches the configured pattern,
     * otherwise a new random UUID.
     */
    String resolveRequestId(ServerHttpRequest request) {
        final String candidate = request.getHeaders().getFirst(requestIdHeader);
        if (candidate != null && requestIdPattern.matcher(candidate).matches()) {
            return candidate;
        }
        if (candidate != null) {
            // The rejected value is deliberately not logged: it is untrusted input.
            log.debug("Replacing invalid inbound {} header with a generated id", requestIdHeader);
        }
        return UUID.randomUUID().toString();
    }

    /**
     * Echo the request id on the response. The header is set immediately (visible to later
     * filters) and again right before commit, so it wins over upstream header copying by the
     * gateway and over any later filter or error handler that overwrites the header.
     */
    private void echoRequestId(ServerHttpResponse response, String requestId) {
        response.getHeaders().set(requestIdHeader, requestId);
        response.beforeCommit(() -> {
            response.getHeaders().set(requestIdHeader, requestId);
            return Mono.empty();
        });
    }

    private Mono<Void> filterWithRequestId(final ServerWebExchange originalExchange, final WebFilterChain chain) {
        // Skip logging/caching for ignored paths (they still got a request id above)
        boolean shouldIgnore = pathPatternsToIgnore.stream()
                .anyMatch(pattern -> pattern.matches(originalExchange.getRequest().getPath()));
        if (shouldIgnore) {
            return chain.filter(originalExchange);
        }

        final Instant startTime = Instant.now();
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
            final BodyCaptureExchange bodyCaptureExchange =
                    new BodyCaptureExchange(exchange, loggingProperties.getMaxBodyBytes());

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
        requestInfo.put("requestId", exchange.getAttribute(REQUEST_ID_ATTRIBUTE));
        requestInfo.put("method", request.getMethod().toString());
        requestInfo.put("path", request.getPath().value());
        requestInfo.put("queryParams", request.getQueryParams());

        // Redact sensitive headers (case-insensitive) using the configurable list.
        requestInfo.put("headers", redactSensitiveHeaders(request.getHeaders()));

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
        responseInfo.put("requestId", exchange.getAttribute(REQUEST_ID_ATTRIBUTE));
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
        responseInfo.put("requestId", exchange.getAttribute(REQUEST_ID_ATTRIBUTE));
        responseInfo.put("status", response.getStatusCode() != null ?
                response.getStatusCode().value() : HttpStatus.INTERNAL_SERVER_ERROR.value());
        responseInfo.put("headers", response.getHeaders());
        responseInfo.put("durationMs", durationMs);

        log.info("Response: {}", responseInfo);
    }

    /**
     * Return a copy of the request headers with values for any name in
     * {@link LoggingProperties#getSensitiveHeaders()} replaced by a redaction
     * marker. The match is case-insensitive on the header name. The returned
     * map is a fresh {@link LinkedHashMap} — modifying it does not touch the
     * underlying request headers.
     */
    Map<String, List<String>> redactSensitiveHeaders(org.springframework.http.HttpHeaders headers) {
        final Set<String> sensitiveLower = new HashSet<>();
        final List<String> configured = loggingProperties.getSensitiveHeaders();
        if (configured != null) {
            for (String h : configured) {
                if (h != null && !h.isBlank()) {
                    sensitiveLower.add(h.toLowerCase(Locale.ROOT));
                }
            }
        }
        final Map<String, List<String>> safe = new LinkedHashMap<>();
        headers.forEach((name, values) -> {
            if (sensitiveLower.contains(name.toLowerCase(Locale.ROOT))) {
                safe.put(name, List.of("[REDACTED]"));
            } else {
                safe.put(name, values);
            }
        });
        return safe;
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

        if (!response.getHeaders().containsHeader("Content-Type")) {
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
