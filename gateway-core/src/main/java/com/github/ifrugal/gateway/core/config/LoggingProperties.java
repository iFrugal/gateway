package com.github.ifrugal.gateway.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.HttpMethod;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Configuration properties for request/response logging.
 *
 * Example configuration:
 * <pre>
 * gateway:
 *   logging:
 *     enabled: true
 *     level: info
 *     max-body-bytes: 65536
 *     sensitive-headers:
 *       - Authorization
 *       - Cookie
 *       - X-API-Key
 *     requests:
 *       - paths: ["/api/users", "/api/accounts"]
 *         methods: [GET, POST]
 *         exclude-body: false
 *       - paths: ["/api/auth/login"]
 *         methods: [POST]
 *         exclude-body: true
 * </pre>
 */
@ConfigurationProperties(prefix = "gateway.logging")
@Data
public class LoggingProperties {

    /**
     * Default cap on the number of bytes captured per request/response body.
     * 64 KB is large enough for the vast majority of real-world JSON payloads
     * without exposing the JVM heap to unbounded growth under load.
     */
    public static final int DEFAULT_MAX_BODY_BYTES = 64 * 1024;

    /**
     * Default list of request/response header names that are redacted from
     * structured log output. Lookup is case-insensitive at runtime.
     */
    public static final List<String> DEFAULT_SENSITIVE_HEADERS = List.of(
            "Authorization",
            "Cookie",
            "Set-Cookie",
            "Proxy-Authorization",
            "X-API-Key",
            "X-Auth-Token"
    );

    /**
     * Enable or disable request/response logging.
     */
    private boolean enabled = true;

    /**
     * Log level for request/response logs.
     */
    private String level = "info";

    /**
     * Path patterns to ignore for both logging and caching (e.g., health checks, swagger).
     * Uses Spring path pattern syntax (Ant-style). If empty, defaults are used.
     */
    private List<String> ignorePaths = new ArrayList<>();

    /**
     * Maximum number of bytes captured from any single request or response body
     * for the purposes of logging and caching. Bodies are still forwarded to the
     * upstream / downstream in full; only the captured copy used by the toolkit
     * is truncated. A value of {@code 0} disables truncation entirely.
     *
     * <p>Set this lower than your effective {@code spring.codec.max-in-memory-size}
     * — the framework limit is the true ceiling; this property is a finer-grained
     * cap to keep heap usage proportional under load.
     */
    private int maxBodyBytes = DEFAULT_MAX_BODY_BYTES;

    /**
     * Header names whose values are redacted from structured log output.
     * Matching is case-insensitive. Set to an empty list to disable redaction
     * (not recommended for any deployment that handles real credentials).
     */
    private List<String> sensitiveHeaders = new ArrayList<>(DEFAULT_SENSITIVE_HEADERS);

    /**
     * List of request configurations for logging.
     */
    private List<RequestConfig> requests = new ArrayList<>();

    @Data
    public static class RequestConfig {
        /**
         * Path patterns to match (supports Ant-style patterns).
         */
        private List<String> paths = new ArrayList<>();

        /**
         * HTTP methods to log (use "*" for all methods).
         */
        private List<String> methods = new ArrayList<>();

        /**
         * Whether to exclude request/response body from logs.
         */
        private boolean excludeBody = false;

        public Set<HttpMethod> getHttpMethods() {
            return methods.stream()
                    .filter(method -> !"*".equals(method))
                    .map(HttpMethod::valueOf)
                    .collect(Collectors.toSet());
        }

        public boolean isWildcardMethod() {
            return methods.contains("*");
        }
    }
}
