package com.github.ifrugal.gateway.core.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LoggingProperties")
class LoggingPropertiesTest {

    @Test
    @DisplayName("should have sensible defaults")
    void defaults() {
        LoggingProperties props = new LoggingProperties();
        assertThat(props.isEnabled()).isTrue();
        assertThat(props.getLevel()).isEqualTo("info");
        assertThat(props.getRequests()).isEmpty();
        assertThat(props.getMaxBodyBytes()).isEqualTo(LoggingProperties.DEFAULT_MAX_BODY_BYTES);
        assertThat(props.getMaxBodyBytes()).isEqualTo(64 * 1024);
        // Default sensitive-header list redacts common credential carriers.
        assertThat(props.getSensitiveHeaders())
                .contains("Authorization", "Cookie", "Set-Cookie",
                        "Proxy-Authorization", "X-API-Key", "X-Auth-Token");
    }

    @Test
    @DisplayName("sensitiveHeaders is mutable so users can extend the default list")
    void sensitiveHeadersIsMutable() {
        LoggingProperties props = new LoggingProperties();
        props.getSensitiveHeaders().add("X-Tenant-Secret");
        assertThat(props.getSensitiveHeaders()).contains("X-Tenant-Secret", "Authorization");
    }

    @Test
    @DisplayName("RequestConfig should parse HTTP methods correctly")
    void requestConfigHttpMethods() {
        LoggingProperties.RequestConfig config = new LoggingProperties.RequestConfig();
        config.setMethods(List.of("GET", "POST"));

        Set<HttpMethod> methods = config.getHttpMethods();
        assertThat(methods).containsExactlyInAnyOrder(HttpMethod.GET, HttpMethod.POST);
        assertThat(config.isWildcardMethod()).isFalse();
    }

    @Test
    @DisplayName("RequestConfig should detect wildcard method")
    void wildcardMethod() {
        LoggingProperties.RequestConfig config = new LoggingProperties.RequestConfig();
        config.setMethods(List.of("*"));

        assertThat(config.isWildcardMethod()).isTrue();
        assertThat(config.getHttpMethods()).isEmpty();
    }

    @Test
    @DisplayName("RequestConfig excludeBody should default to false")
    void excludeBodyDefault() {
        LoggingProperties.RequestConfig config = new LoggingProperties.RequestConfig();
        assertThat(config.isExcludeBody()).isFalse();
    }
}
