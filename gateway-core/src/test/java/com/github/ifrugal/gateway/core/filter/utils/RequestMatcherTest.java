package com.github.ifrugal.gateway.core.filter.utils;

import com.github.ifrugal.gateway.core.config.CachingProperties;
import com.github.ifrugal.gateway.core.config.LoggingProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RequestMatcher")
class RequestMatcherTest {

    // --- Logging Config Matching ---

    @Test
    @DisplayName("should match logging config for matching path and wildcard method")
    void matchLogConfigWildcard() {
        LoggingProperties props = new LoggingProperties();
        props.setEnabled(true);
        LoggingProperties.RequestConfig config = new LoggingProperties.RequestConfig();
        config.setPaths(List.of("/api/**"));
        config.setMethods(List.of("*"));
        props.setRequests(List.of(config));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/users").build());

        Optional<LoggingProperties.RequestConfig> result =
                RequestMatcher.findMatchingLogConfig(exchange, props);

        assertThat(result).isPresent();
    }

    @Test
    @DisplayName("should match logging config for matching path and specific method")
    void matchLogConfigSpecificMethod() {
        LoggingProperties props = new LoggingProperties();
        props.setEnabled(true);
        LoggingProperties.RequestConfig config = new LoggingProperties.RequestConfig();
        config.setPaths(List.of("/api/**"));
        config.setMethods(List.of("GET", "POST"));
        props.setRequests(List.of(config));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/users").build());

        Optional<LoggingProperties.RequestConfig> result =
                RequestMatcher.findMatchingLogConfig(exchange, props);

        assertThat(result).isPresent();
    }

    @Test
    @DisplayName("should not match logging config when method does not match")
    void noMatchLogConfigWrongMethod() {
        LoggingProperties props = new LoggingProperties();
        props.setEnabled(true);
        LoggingProperties.RequestConfig config = new LoggingProperties.RequestConfig();
        config.setPaths(List.of("/api/**"));
        config.setMethods(List.of("POST"));
        props.setRequests(List.of(config));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/users").build());

        Optional<LoggingProperties.RequestConfig> result =
                RequestMatcher.findMatchingLogConfig(exchange, props);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("should not match logging config when path does not match")
    void noMatchLogConfigWrongPath() {
        LoggingProperties props = new LoggingProperties();
        props.setEnabled(true);
        LoggingProperties.RequestConfig config = new LoggingProperties.RequestConfig();
        config.setPaths(List.of("/api/**"));
        config.setMethods(List.of("*"));
        props.setRequests(List.of(config));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/health").build());

        Optional<LoggingProperties.RequestConfig> result =
                RequestMatcher.findMatchingLogConfig(exchange, props);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("should return empty when logging is disabled")
    void loggingDisabled() {
        LoggingProperties props = new LoggingProperties();
        props.setEnabled(false);
        LoggingProperties.RequestConfig config = new LoggingProperties.RequestConfig();
        config.setPaths(List.of("/api/**"));
        config.setMethods(List.of("*"));
        props.setRequests(List.of(config));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/users").build());

        Optional<LoggingProperties.RequestConfig> result =
                RequestMatcher.findMatchingLogConfig(exchange, props);

        assertThat(result).isEmpty();
    }

    // --- Cache Rule Matching ---

    @Test
    @DisplayName("should match cache rule for matching path and method")
    void matchCacheRule() {
        CachingProperties props = new CachingProperties();
        props.setEnabled(true);
        props.setDefaultTtl(3600);
        CachingProperties.CacheRuleConfig rule = new CachingProperties.CacheRuleConfig();
        rule.setPaths(List.of("/api/products/**"));
        rule.setMethods(List.of("GET"));
        rule.setTtl(1800);
        props.setRules(List.of(rule));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/products/123").build());

        Optional<RequestMatcher.CacheRule> result =
                RequestMatcher.findMatchingCacheRule(exchange, props);

        assertThat(result).isPresent();
        assertThat(result.get().getTtlSeconds()).isEqualTo(1800);
    }

    @Test
    @DisplayName("should use default TTL when rule TTL is 0")
    void cacheRuleDefaultTtl() {
        CachingProperties props = new CachingProperties();
        props.setEnabled(true);
        props.setDefaultTtl(3600);
        CachingProperties.CacheRuleConfig rule = new CachingProperties.CacheRuleConfig();
        rule.setPaths(List.of("/api/**"));
        rule.setMethods(List.of("*"));
        rule.setTtl(0);
        props.setRules(List.of(rule));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/data").build());

        Optional<RequestMatcher.CacheRule> result =
                RequestMatcher.findMatchingCacheRule(exchange, props);

        assertThat(result).isPresent();
        assertThat(result.get().getTtlSeconds()).isEqualTo(3600);
    }

    @Test
    @DisplayName("should return empty when caching is disabled")
    void cachingDisabled() {
        CachingProperties props = new CachingProperties();
        props.setEnabled(false);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/data").build());

        Optional<RequestMatcher.CacheRule> result =
                RequestMatcher.findMatchingCacheRule(exchange, props);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("should not match cache rule when method does not match")
    void cacheRuleWrongMethod() {
        CachingProperties props = new CachingProperties();
        props.setEnabled(true);
        CachingProperties.CacheRuleConfig rule = new CachingProperties.CacheRuleConfig();
        rule.setPaths(List.of("/api/**"));
        rule.setMethods(List.of("GET"));
        rule.setTtl(600);
        props.setRules(List.of(rule));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/data").build());

        Optional<RequestMatcher.CacheRule> result =
                RequestMatcher.findMatchingCacheRule(exchange, props);

        assertThat(result).isEmpty();
    }
}
