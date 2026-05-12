package com.github.ifrugal.gateway.core.filter.utils;

import com.github.ifrugal.gateway.core.config.CachingProperties;
import com.github.ifrugal.gateway.core.config.LoggingProperties;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;

import java.util.Optional;

/**
 * Utility class for matching requests against configured logging and caching rules.
 */
public class RequestMatcher {

    private static final AntPathMatcher pathMatcher = new AntPathMatcher();

    /**
     * Find matching logging configuration for a request.
     *
     * @param exchange Server web exchange
     * @param loggingProperties Logging properties
     * @return Optional containing matched config, or empty if no match
     */
    public static Optional<LoggingProperties.RequestConfig> findMatchingLogConfig(
            ServerWebExchange exchange,
            LoggingProperties loggingProperties) {

        if (!loggingProperties.isEnabled()) {
            return Optional.empty();
        }

        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();
        HttpMethod method = request.getMethod();

        return loggingProperties.getRequests().stream()
                .filter(config -> matchesPath(config.getPaths(), path))
                .filter(config -> matchesMethod(config, method))
                .findFirst();
    }

    /**
     * Find matching cache rule for a request.
     *
     * @param exchange Server web exchange
     * @param cachingProperties Caching properties
     * @return Optional containing matched rule, or empty if no match
     */
    public static Optional<CacheRule> findMatchingCacheRule(
            ServerWebExchange exchange,
            CachingProperties cachingProperties) {

        if (!cachingProperties.isEnabled()) {
            return Optional.empty();
        }

        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();
        HttpMethod method = request.getMethod();

        return cachingProperties.getRules().stream()
                .filter(rule -> matchesPath(rule.getPaths(), path))
                .filter(rule -> matchesMethod(rule, method))
                .map(rule -> new CacheRule(rule.getTtl() > 0 ? rule.getTtl() : cachingProperties.getDefaultTtl()))
                .findFirst();
    }

    private static boolean matchesPath(Iterable<String> patterns, String path) {
        for (String pattern : patterns) {
            if (pathMatcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesMethod(LoggingProperties.RequestConfig config, HttpMethod method) {
        return config.isWildcardMethod() || config.getHttpMethods().contains(method);
    }

    private static boolean matchesMethod(CachingProperties.CacheRuleConfig rule, HttpMethod method) {
        return rule.isWildcardMethod() || rule.getHttpMethods().contains(method);
    }

    /**
     * Represents a matched cache rule with its TTL.
     */
    public static class CacheRule {
        private final long ttlSeconds;

        public CacheRule(long ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }

        public long getTtlSeconds() {
            return ttlSeconds;
        }
    }
}
