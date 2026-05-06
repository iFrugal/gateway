package com.github.ifrugal.gateway.core.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SecurityProperties")
class SecurityPropertiesTest {

    @Test
    @DisplayName("should have sensible defaults")
    void defaults() {
        SecurityProperties props = new SecurityProperties();
        assertThat(props.isEnabled()).isTrue();
        assertThat(props.getGuestAllowedPaths()).isEmpty();
        assertThat(props.getOauth2()).isNotNull();
        assertThat(props.getOauth2().isEnabled()).isFalse();
    }

    @Test
    @DisplayName("OAuth2Config should have sensible defaults")
    void oauth2Defaults() {
        SecurityProperties.OAuth2Config oauth2 = new SecurityProperties.OAuth2Config();
        assertThat(oauth2.isEnabled()).isFalse();
        assertThat(oauth2.getProvider()).isNotNull();
        assertThat(oauth2.getClient()).isNotNull();
    }

    @Test
    @DisplayName("ClientConfig should have default scopes")
    void clientDefaults() {
        SecurityProperties.ClientConfig client = new SecurityProperties.ClientConfig();
        assertThat(client.getScopes()).isEqualTo("openid,profile,email");
    }

    @Test
    @DisplayName("ProviderConfig should default userNameAttribute to sub")
    void providerDefaults() {
        SecurityProperties.ProviderConfig provider = new SecurityProperties.ProviderConfig();
        assertThat(provider.getUserNameAttribute()).isEqualTo("sub");
    }
}
