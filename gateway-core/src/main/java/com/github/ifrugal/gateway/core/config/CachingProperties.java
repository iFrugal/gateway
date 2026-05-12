package com.github.ifrugal.gateway.core.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.HttpMethod;
import org.springframework.validation.annotation.Validated;

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
@Validated
@Data
public class CachingProperties {

    /**
     * Enable or disable response caching.
     */
    private boolean enabled = false;

    /**
     * Cache provider to use (currently only 'caffeine' is supported in-tree).
     * The {@code CacheProvider} interface is an SPI — register a {@code @Bean}
     * of type {@code CacheProvider} to substitute another backend.
     */
    @NotBlank(message = "gateway.caching.provider must not be blank")
    private String provider = "caffeine";

    /**
     * Default TTL in seconds for cached responses.
     */
    @Positive(message = "gateway.caching.default-ttl must be positive (seconds)")
    private long defaultTtl = 86400; // 1 day

    /**
     * Maximum number of entries in the cache.
     */
    @Min(value = 1, message = "gateway.caching.max-size must be at least 1")
    private int maxSize = 10000;

    /**
     * List of caching rules for different paths.
     */
    @Valid
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
        @Positive(message = "rule ttl must be positive (seconds)")
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
