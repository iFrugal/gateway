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
