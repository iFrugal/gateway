package com.github.ifrugal.gateway.core.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CorsProperties")
class CorsPropertiesTest {

    @Test
    @DisplayName("should have sensible defaults")
    void defaults() {
        CorsProperties props = new CorsProperties();
        assertThat(props.isEnabled()).isTrue();
        assertThat(props.getAllowedOrigins()).isEmpty();
        assertThat(props.getAllowedMethods()).containsExactly("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
        assertThat(props.getAllowedHeaders()).containsExactly("*");
        assertThat(props.getExposedHeaders()).isEmpty();
        assertThat(props.getMaxAge()).isEqualTo(3600);
        assertThat(props.isAllowCredentials()).isTrue();
    }
}
