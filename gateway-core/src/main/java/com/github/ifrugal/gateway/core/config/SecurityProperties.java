package com.github.ifrugal.gateway.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration properties for security settings.
 *
 * Example configuration:
 * <pre>
 * gateway:
 *   security:
 *     enabled: true
 *     guest-allowed-paths:
 *       - /api/public/**
 *       - /search/**
 *     oauth2:
 *       enabled: true
 *       provider:
 *         issuer-uri: https://auth.example.com
 *         authorization-uri: https://auth.example.com/oauth2/authorize
 *         token-uri: https://auth.example.com/oauth2/token
 *         jwk-set-uri: https://auth.example.com/.well-known/jwks.json
 *         user-info-uri: https://auth.example.com/oauth2/userInfo
 *         user-name-attribute: sub
 *       client:
 *         id: ${OAUTH2_CLIENT_ID}
 *         secret: ${OAUTH2_CLIENT_SECRET}
 *         scopes: openid,profile,email
 * </pre>
 */
@ConfigurationProperties(prefix = "gateway.security")
@Data
public class SecurityProperties {

    /**
     * Enable or disable security configuration.
     */
    private boolean enabled = true;

    /**
     * Paths that don't require authentication.
     */
    private List<String> guestAllowedPaths = new ArrayList<>();

    /**
     * OAuth2 configuration.
     */
    private OAuth2Config oauth2 = new OAuth2Config();

    @Data
    public static class OAuth2Config {
        /**
         * Enable or disable OAuth2.
         */
        private boolean enabled = false;

        /**
         * OAuth2 provider configuration.
         */
        private ProviderConfig provider = new ProviderConfig();

        /**
         * OAuth2 client configuration.
         */
        private ClientConfig client = new ClientConfig();
    }

    @Data
    public static class ProviderConfig {
        private String issuerUri;
        private String authorizationUri;
        private String tokenUri;
        private String jwkSetUri;
        private String userInfoUri;
        private String userNameAttribute = "sub";
    }

    @Data
    public static class ClientConfig {
        private String id;
        private String secret;
        private String scopes = "openid,profile,email";
        private String redirectUri = "{baseUrl}/swagger-ui/oauth2-redirect.html";
    }
}
