package com.github.ifrugal.gateway.core.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
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

        /**
         * JWT claim -> outbound-request-header propagation rules. When the
         * authenticated request carries a {@code JwtAuthenticationToken},
         * each rule reads a claim from the JWT and writes the resulting
         * value (optionally extracted via named-capture-group regex,
         * optionally defaulted) to a downstream header.
         *
         * <p>Empty list (the default) means no propagation. See
         * {@code docs/security.md#jwt-claims-to-headers} for the full
         * recipe and {@code JwtClaimsToHeadersWebFilter} for the runtime
         * behaviour.
         */
        @Valid
        private List<ClaimHeaderRule> claimHeaders = new ArrayList<>();

        /**
         * Compile all {@code extract} regex patterns. Called from the
         * autoconfig once at startup so any malformed pattern fails the
         * application at boot, not on the first matching request.
         */
        public void compilePatterns() {
            for (ClaimHeaderRule rule : claimHeaders) {
                rule.compilePattern();
            }
        }
    }

    /**
     * Single rule for the claim-headers feature. Read by
     * {@code JwtClaimsToHeadersWebFilter}. The per-request interpretation is:
     * <ol>
     *   <li>If {@link #ifPreviousBlank} is {@code true} and an earlier rule
     *       for the same {@link #header} already produced a value, skip
     *       this rule.</li>
     *   <li>Look up {@link #claim} in the JWT claims. If absent, use
     *       {@link #defaultValue}. {@code null} default means
     *       "don't write the header".</li>
     *   <li>If {@link #extract} is set, run the regex against the claim
     *       value. On no-match, use {@link #defaultValue}. On match,
     *       return {@link #namedGroup}'s captured value, or the entire
     *       matched substring when {@code namedGroup} is unset.</li>
     * </ol>
     */
    @Data
    public static class ClaimHeaderRule {

        /** Name of the outbound header to write. Required. */
        @NotBlank(message = "claim-header rule must declare 'header'")
        private String header;

        /** Name of the JWT claim to read. Required. */
        @NotBlank(message = "claim-header rule must declare 'claim'")
        private String claim;

        /**
         * Optional regex with named capture groups. When set, the claim's
         * value is matched against this pattern. When {@code namedGroup} is
         * also set, the captured group's value is written to the header;
         * otherwise the entire matched substring is written.
         */
        private String extract;

        /**
         * Optional name of the regex capture group to use as the header
         * value. Ignored when {@code extract} is not set.
         */
        private String namedGroup;

        /**
         * Optional literal default. Used when the claim is absent or when
         * the regex doesn't match. {@code null} means "skip the header
         * entirely in those cases".
         */
        private String defaultValue;

        /**
         * Optional fallback flag. When {@code true}, this rule only fires
         * if no earlier rule has already written a value for the same
         * {@link #header}. Useful for "try this claim first, fall back to
         * that one" patterns.
         */
        private boolean ifPreviousBlank;

        /**
         * Compiled {@link java.util.regex.Pattern}. Populated by
         * {@link OAuth2Config#compilePatterns()} at startup so the filter
         * hot path never compiles. {@code transient} so it doesn't get
         * serialised back into application.yml dumps.
         */
        @JsonIgnore
        private transient java.util.regex.Pattern compiledPattern;

        void compilePattern() {
            if (extract == null || extract.isEmpty()) {
                this.compiledPattern = null;
                return;
            }
            try {
                this.compiledPattern = java.util.regex.Pattern.compile(extract);
            } catch (java.util.regex.PatternSyntaxException e) {
                throw new IllegalStateException(
                        "Invalid regex in gateway.security.oauth2.claim-headers entry for header '"
                                + header + "': " + e.getMessage(), e);
            }
        }
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
