package com.github.ifrugal.gateway.autoconfigure;

import com.github.ifrugal.gateway.core.config.SecurityProperties.ClaimHeaderRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("JwtClaimsToHeadersWebFilter")
class JwtClaimsToHeadersWebFilterTest {

    @Nested
    @DisplayName("pass-through behaviour")
    class PassThrough {

        @Test
        @DisplayName("empty rules list -> filter is a no-op even when JWT is present")
        void emptyRulesPassThrough() {
            JwtClaimsToHeadersWebFilter filter = new JwtClaimsToHeadersWebFilter(List.of());
            ServerWebExchange exchange = newExchange();
            AtomicReference<HttpHeaders> downstream = new AtomicReference<>();
            WebFilterChain chain = ex -> {
                downstream.set(ex.getRequest().getHeaders());
                return Mono.empty();
            };

            StepVerifier.create(filter.filter(exchange, chain).contextWrite(jwtContext(Map.of("sub", "alice"))))
                    .verifyComplete();

            assertThat(downstream.get()).doesNotContainKey("x-user-id");
        }

        @Test
        @DisplayName("no JWT in context -> filter is a no-op")
        void noJwtPassThrough() {
            JwtClaimsToHeadersWebFilter filter = new JwtClaimsToHeadersWebFilter(
                    List.of(rule("x-user-id", "sub", null, null, null, false)));
            ServerWebExchange exchange = newExchange();
            AtomicReference<HttpHeaders> downstream = new AtomicReference<>();
            WebFilterChain chain = ex -> {
                downstream.set(ex.getRequest().getHeaders());
                return Mono.empty();
            };

            // No security context at all
            StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
            assertThat(downstream.get()).doesNotContainKey("x-user-id");
        }

        @Test
        @DisplayName("non-JWT authentication in context -> filter is a no-op")
        void nonJwtAuthPassThrough() {
            JwtClaimsToHeadersWebFilter filter = new JwtClaimsToHeadersWebFilter(
                    List.of(rule("x-user-id", "sub", null, null, null, false)));
            ServerWebExchange exchange = newExchange();
            AtomicReference<HttpHeaders> downstream = new AtomicReference<>();
            WebFilterChain chain = ex -> {
                downstream.set(ex.getRequest().getHeaders());
                return Mono.empty();
            };

            SecurityContext ctx = new SecurityContextImpl(new TestingAuthenticationToken("alice", "creds"));
            StepVerifier.create(filter.filter(exchange, chain)
                            .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(ctx))))
                    .verifyComplete();
            assertThat(downstream.get()).doesNotContainKey("x-user-id");
        }
    }

    @Nested
    @DisplayName("1:1 claim → header mapping")
    class SimpleMapping {

        @Test
        @DisplayName("each rule reads its claim and writes the header verbatim")
        void writesHeaderFromClaim() {
            JwtClaimsToHeadersWebFilter filter = new JwtClaimsToHeadersWebFilter(List.of(
                    rule("x-user-id", "sub", null, null, null, false),
                    rule("x-tenant-id", "tid", null, null, null, false),
                    rule("x-role", "jobTitle", null, null, null, false)
            ));
            AtomicReference<HttpHeaders> downstream = new AtomicReference<>();

            runFilter(filter, downstream, Map.of(
                    "sub", "alice",
                    "tid", "tenant-a",
                    "jobTitle", "admin"));

            assertThat(downstream.get().getFirst("x-user-id")).isEqualTo("alice");
            assertThat(downstream.get().getFirst("x-tenant-id")).isEqualTo("tenant-a");
            assertThat(downstream.get().getFirst("x-role")).isEqualTo("admin");
        }

        @Test
        @DisplayName("missing claim with no default → header not written")
        void missingClaimNoDefault() {
            JwtClaimsToHeadersWebFilter filter = new JwtClaimsToHeadersWebFilter(List.of(
                    rule("x-user-id", "sub", null, null, null, false),
                    rule("x-role", "jobTitle", null, null, null, false)
            ));
            AtomicReference<HttpHeaders> downstream = new AtomicReference<>();

            runFilter(filter, downstream, Map.of("sub", "alice")); // no jobTitle

            assertThat(downstream.get().getFirst("x-user-id")).isEqualTo("alice");
            assertThat(downstream.get()).doesNotContainKey("x-role");
        }

        @Test
        @DisplayName("missing claim with default → default value is written")
        void missingClaimWithDefault() {
            JwtClaimsToHeadersWebFilter filter = new JwtClaimsToHeadersWebFilter(List.of(
                    rule("x-system-name", "system", null, null, "nodeJs-backend", false)
            ));
            AtomicReference<HttpHeaders> downstream = new AtomicReference<>();

            runFilter(filter, downstream, Map.of("sub", "alice"));

            assertThat(downstream.get().getFirst("x-system-name")).isEqualTo("nodeJs-backend");
        }
    }

    @Nested
    @DisplayName("regex extraction")
    class RegexExtraction {

        @Test
        @DisplayName("named group captures substring of claim value")
        void namedGroupExtraction() {
            ClaimHeaderRule r = rule("x-tenant-id", "iss",
                    "https://(?<tenantName>[^.]+)\\.b2clogin\\.com/tfp/(?<tenantId>[^/]+)/",
                    "tenantId", null, false);
            r.compilePattern();

            JwtClaimsToHeadersWebFilter filter = new JwtClaimsToHeadersWebFilter(List.of(r));
            AtomicReference<HttpHeaders> downstream = new AtomicReference<>();

            runFilter(filter, downstream, Map.of(
                    "iss", "https://contoso.b2clogin.com/tfp/abc-123-def/B2C_1_signin/v2.0/"));

            assertThat(downstream.get().getFirst("x-tenant-id")).isEqualTo("abc-123-def");
        }

        @Test
        @DisplayName("regex with no named-group writes the entire match")
        void regexWholeMatchWhenNoNamedGroup() {
            ClaimHeaderRule r = rule("x-issuer", "iss",
                    "https://[^/]+", null, null, false);
            r.compilePattern();

            JwtClaimsToHeadersWebFilter filter = new JwtClaimsToHeadersWebFilter(List.of(r));
            AtomicReference<HttpHeaders> downstream = new AtomicReference<>();

            runFilter(filter, downstream, Map.of("iss", "https://idp.example.com/realms/x"));

            assertThat(downstream.get().getFirst("x-issuer")).isEqualTo("https://idp.example.com");
        }

        @Test
        @DisplayName("regex no-match with default → default is written")
        void regexNoMatchWithDefault() {
            ClaimHeaderRule r = rule("x-tenant-id", "iss",
                    "https://(?<t>[^.]+)\\.b2clogin\\.com/",
                    "t", "no-tenant", false);
            r.compilePattern();

            JwtClaimsToHeadersWebFilter filter = new JwtClaimsToHeadersWebFilter(List.of(r));
            AtomicReference<HttpHeaders> downstream = new AtomicReference<>();

            runFilter(filter, downstream, Map.of("iss", "https://idp.example.com/realms/x"));

            assertThat(downstream.get().getFirst("x-tenant-id")).isEqualTo("no-tenant");
        }

        @Test
        @DisplayName("regex no-match without default → header not written")
        void regexNoMatchNoDefault() {
            ClaimHeaderRule r = rule("x-tenant-id", "iss",
                    "^never-matches-anything$", null, null, false);
            r.compilePattern();

            JwtClaimsToHeadersWebFilter filter = new JwtClaimsToHeadersWebFilter(List.of(r));
            AtomicReference<HttpHeaders> downstream = new AtomicReference<>();

            runFilter(filter, downstream, Map.of("iss", "https://idp.example.com/realms/x"));

            assertThat(downstream.get()).doesNotContainKey("x-tenant-id");
        }

        @Test
        @DisplayName("malformed regex fails loud at compile time")
        void malformedRegexFailsAtCompile() {
            ClaimHeaderRule r = rule("x-bad", "iss", "(unclosed group", null, null, false);
            assertThatThrownBy(r::compilePattern)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Invalid regex in gateway.security.oauth2.claim-headers");
        }

        @Test
        @DisplayName("named-group that doesn't exist in pattern → throw on first match")
        void wrongNamedGroupReference() {
            ClaimHeaderRule r = rule("x-tenant", "iss", "(?<tenant>[a-z]+)", "nonExistent", null, false);
            r.compilePattern();

            JwtClaimsToHeadersWebFilter filter = new JwtClaimsToHeadersWebFilter(List.of(r));
            WebFilterChain chain = ex -> Mono.empty();
            ServerWebExchange exchange = newExchange();

            StepVerifier.create(filter.filter(exchange, chain)
                            .contextWrite(jwtContext(Map.of("iss", "tenant-x"))))
                    .expectErrorSatisfies(t -> {
                        assertThat(t).isInstanceOf(IllegalStateException.class);
                        assertThat(t.getMessage()).contains("nonExistent");
                    })
                    .verify();
        }
    }

    @Nested
    @DisplayName("if-previous-blank fallback")
    class FallbackChain {

        @Test
        @DisplayName("second rule only fires when first produced no value")
        void fallsBackToSecondRule() {
            ClaimHeaderRule first = rule("x-tenant-id", "iss",
                    "https://(?<t>[^.]+)\\.b2clogin\\.com/", "t", null, false);
            first.compilePattern();
            ClaimHeaderRule second = rule("x-tenant-id", "tid", null, null, null, true);

            JwtClaimsToHeadersWebFilter filter = new JwtClaimsToHeadersWebFilter(List.of(first, second));
            AtomicReference<HttpHeaders> downstream = new AtomicReference<>();

            // Case A: first rule succeeds → second is skipped (winner: regex extraction)
            runFilter(filter, downstream, Map.of(
                    "iss", "https://contoso.b2clogin.com/tfp/abc/B2C_1/v2.0/",
                    "tid", "fallback-tenant"));
            assertThat(downstream.get().getFirst("x-tenant-id")).isEqualTo("contoso");

            // Case B: first rule misses → second fires (winner: tid)
            AtomicReference<HttpHeaders> downstream2 = new AtomicReference<>();
            runFilter(filter, downstream2, Map.of(
                    "iss", "https://idp.example.com/realms/x",
                    "tid", "fallback-tenant"));
            assertThat(downstream2.get().getFirst("x-tenant-id")).isEqualTo("fallback-tenant");
        }
    }

    // ---- helpers --------------------------------------------------------

    private static ClaimHeaderRule rule(String header, String claim, String extract,
                                        String namedGroup, String defaultValue,
                                        boolean ifPreviousBlank) {
        ClaimHeaderRule r = new ClaimHeaderRule();
        r.setHeader(header);
        r.setClaim(claim);
        r.setExtract(extract);
        r.setNamedGroup(namedGroup);
        r.setDefaultValue(defaultValue);
        r.setIfPreviousBlank(ifPreviousBlank);
        return r;
    }

    private static ServerWebExchange newExchange() {
        MockServerHttpRequest req = MockServerHttpRequest.get("/api/anything").build();
        return MockServerWebExchange.from(req);
    }

    private static Context jwtContext(Map<String, Object> claims) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claims(c -> c.putAll(claims))
                .build();
        JwtAuthenticationToken auth = new JwtAuthenticationToken(jwt);
        auth.setAuthenticated(true);
        SecurityContext ctx = new SecurityContextImpl(auth);
        return ReactiveSecurityContextHolder.withSecurityContext(Mono.just(ctx));
    }

    private static void runFilter(JwtClaimsToHeadersWebFilter filter,
                                  AtomicReference<HttpHeaders> sink,
                                  Map<String, Object> claims) {
        ServerWebExchange exchange = newExchange();
        WebFilterChain chain = ex -> {
            sink.set(ex.getRequest().getHeaders());
            return Mono.empty();
        };
        StepVerifier.create(filter.filter(exchange, chain).contextWrite(jwtContext(claims)))
                .verifyComplete();
    }
}
