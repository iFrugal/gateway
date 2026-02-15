package com.github.ifrugal.gateway.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.HttpMethod;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Configuration properties for response caching.
 *
 * Example configuration:
 * <pre>
 * gateway:
 *   caching:
 *     enabled: true
 *     provider: caffeine
 *     default-ttl: 86400
 *     max-size: 10000
 *     rules:
 *       - paths: ["/api/products", "/api/categories"]
 *         methods: [GET]
 *         ttl: 3600
 * </pre>
 */
@ConfigurationProperties(prefix = "gateway.caching")
@Data
public class CachingProperties {

    /**
     * Enable or disable response caching.
     */
    private boolean enabled = false;

    /**
     * Cache provider to use (currently only 'caffeine' is supported).
     */
    private String provider = "caffeine";

    /**
     * Default TTL in seconds for cached responses.
     */
    private long defaultTtl = 86400; // 1 day

    /**
     * Maximum number of entries in the cache.
     */
    private int maxSize = 10000;

    /**
     * List of caching rules for different paths.
     */
    private List<CacheRuleConfig> rules = new ArrayList<>();

    @Data
    public static class CacheRuleConfig {
        /**
         * Path patterns to match (supports Ant-style patterns).
         */
        private List<String> paths = new ArrayList<>();

        /**
         * HTTP methods to cache (use "*" for all methods).
         */
        private List<String> methods = new ArrayList<>();

        /**
         * TTL in seconds for this rule (overrides default-ttl).
         */
        private long ttl;

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
