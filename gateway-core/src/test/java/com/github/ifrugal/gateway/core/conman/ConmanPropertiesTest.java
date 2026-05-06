package com.github.ifrugal.gateway.core.conman;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ConmanProperties")
class ConmanPropertiesTest {

    @Test
    @DisplayName("should have sensible defaults")
    void defaults() {
        ConmanProperties props = new ConmanProperties();
        assertThat(props.isEnabled()).isFalse();
        assertThat(props.getServletUriMappings()).containsExactly("/mock/**");
        assertThat(props.getMappingFiles()).containsExactly("classpath:conman.yml");
        assertThat(props.getBannerPath()).isEqualTo("classpath:conman-banner.txt");
    }

    @Test
    @DisplayName("should allow customization")
    void customization() {
        ConmanProperties props = new ConmanProperties();
        props.setEnabled(true);
        props.setServletUriMappings(java.util.List.of("/api/mock/**", "/test/**"));

        assertThat(props.isEnabled()).isTrue();
        assertThat(props.getServletUriMappings()).containsExactly("/api/mock/**", "/test/**");
    }
}
