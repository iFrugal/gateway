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
     * Enable or disable request/response logging.
     */
    private boolean enabled = true;

    /**
     * Log level for request/response logs.
     */
    private String level = "info";

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
