package com.github.ifrugal.gateway.core.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration properties for security settings.
 *
 * <p>Example configuration:
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
@Validated
@Data
public class SecurityProperties {

    /**
     * Enable or disable security configuration. Defaults to {@code false} —
     * matches the behaviour of {@link
     * org.springframework.boot.autoconfigure.condition.ConditionalOnProperty}
     * on the security auto-configuration ({@code matchIfMissing = false}).
     * Admin endpoints ({@code /gateway/cache/**}, {@code /conman/admin/**})
     * are reachable without authentication until this is set to {@code true}.
     */
    private boolean enabled = false;

    /**
     * Paths that don't require authentication. Always-protected endpoints
     * (cache and conman admin) cannot be permitted via this list — they are
     * hardcoded in {@code SecurityAutoConfiguration}.
     */
    private List<String> guestAllowedPaths = new ArrayList<>();

    /**
     * OAuth2 configuration.
     */
    @Valid
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
        @Valid
        private ProviderConfig provider = new ProviderConfig();

        /**
         * OAuth2 client configuration.
         */
        @Valid
        private ClientConfig client = new ClientConfig();
    }

    @Data
    public static class ProviderConfig {
        private String issuerUri;
        private String authorizationUri;
        private String tokenUri;
        private String jwkSetUri;
        private String userInfoUri;
        @NotBlank(message = "gateway.security.oauth2.provider.user-name-attribute must not be blank")
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
