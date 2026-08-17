package com.github.ifrugal.gateway.autoconfigure;

import com.github.ifrugal.gateway.core.config.SecurityProperties.ClaimHeaderRule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads claims from the authenticated {@link JwtAuthenticationToken} on the
 * incoming request and writes them as headers on the outbound request, so
 * downstream services can act on identity / tenant / role information
 * without re-parsing the JWT.
 *
 * <p>Behaviour is driven by
 * {@code gateway.security.oauth2.claim-headers} — a list of
 * {@link ClaimHeaderRule}. See {@code docs/security.md#jwt-claims-to-headers}
 * for the full recipe.
 *
 * <p>Runs at order
 * {@link org.springframework.security.web.server.WebFilterChainProxy} + 1
 * (i.e. immediately after the Spring Security filter chain has authenticated
 * the request and populated the {@link ReactiveSecurityContextHolder}). If
 * the request has no JWT — anonymous, basic auth, etc. — the filter is a
 * pass-through and no headers are added.
 *
 * <p>Each rule's regex (when present) is compiled once at startup by
 * {@code SecurityProperties.OAuth2Config#compilePatterns()}. The filter
 * hot path never compiles, so a malformed pattern is a boot-time error
 * rather than a per-request stack trace.
 */
@Slf4j
public class JwtClaimsToHeadersWebFilter implements WebFilter, Ordered {

    /**
     * Run immediately after Spring Security's
     * {@code WebFilterChainProxy} (which sits at order {@code -100} in
     * Spring Boot's defaults). Hardcoded value rather than the magic
     * constant so we don't pull in the security-config dependency for a
     * single integer.
     */
    private static final int DEFAULT_ORDER = -99;

    private final List<ClaimHeaderRule> rules;
    private final int order;

    public JwtClaimsToHeadersWebFilter(List<ClaimHeaderRule> rules) {
        this(rules, DEFAULT_ORDER);
    }

    /**
     * @param rules pre-compiled rule list (callers should invoke
     *              {@code OAuth2Config.compilePatterns()} before constructing
     *              the filter so regex compilation is eager)
     * @param order Spring {@link Ordered} value
     */
    public JwtClaimsToHeadersWebFilter(List<ClaimHeaderRule> rules, int order) {
        this.rules = List.copyOf(rules);
        this.order = order;
    }

    @Override
    public int getOrder() {
        return order;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (rules.isEmpty()) {
            return chain.filter(exchange);
        }
        // applyRules must NOT invoke the chain inside flatMap: chain.filter
        // returns Mono<Void>, which completes empty, so a trailing
        // switchIfEmpty would fire as well and run the chain a second time
        // with the unmutated exchange. Resolve the (possibly mutated)
        // exchange first, then invoke the chain exactly once.
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(JwtAuthenticationToken.class::isInstance)
                .cast(JwtAuthenticationToken.class)
                .map(JwtAuthenticationToken::getToken)
                .map(jwt -> applyRules(exchange, jwt))
                .defaultIfEmpty(exchange)
                .flatMap(chain::filter);
    }

    private ServerWebExchange applyRules(ServerWebExchange exchange, Jwt jwt) {
        Map<String, Object> claims = jwt.getClaims();
        // Track per-header winning value so `if-previous-blank` rules can
        // detect that an earlier rule already produced a value.
        Map<String, String> resolved = new LinkedHashMap<>();

        for (ClaimHeaderRule rule : rules) {
            if (rule.isIfPreviousBlank() && resolved.containsKey(rule.getHeader())) {
                continue;
            }
            String value = resolveValue(rule, claims);
            if (value != null) {
                resolved.put(rule.getHeader(), value);
            }
        }

        if (resolved.isEmpty()) {
            return exchange;
        }

        ServerHttpRequest.Builder builder = exchange.getRequest().mutate();
        resolved.forEach(builder::header);
        return exchange.mutate().request(builder.build()).build();
    }

    /**
     * Resolve the header value for a single rule. Returns {@code null} when
     * no header should be written (claim absent + no default, or regex
     * no-match + no default).
     */
    private String resolveValue(ClaimHeaderRule rule, Map<String, Object> claims) {
        Object claimValue = claims.get(rule.getClaim());
        if (claimValue == null) {
            return rule.getDefaultValue();
        }

        String stringValue = claimValue.toString();

        Pattern pattern = rule.getCompiledPattern();
        if (pattern == null) {
            return stringValue;
        }

        Matcher matcher = pattern.matcher(stringValue);
        if (!matcher.find()) {
            log.debug("claim-headers rule '{}': regex did not match claim '{}' value '{}'; using default",
                    rule.getHeader(), rule.getClaim(), stringValue);
            return rule.getDefaultValue();
        }

        if (rule.getNamedGroup() != null && !rule.getNamedGroup().isEmpty()) {
            try {
                String captured = matcher.group(rule.getNamedGroup());
                return captured != null ? captured : rule.getDefaultValue();
            } catch (IllegalArgumentException e) {
                // Named group doesn't exist in the compiled pattern — a
                // configuration bug. Fail loud on the first match so the
                // operator notices.
                throw new IllegalStateException(
                        "claim-headers rule '" + rule.getHeader()
                                + "' references named group '" + rule.getNamedGroup()
                                + "' which is not declared in the extract regex.", e);
            }
        }
        // No named-group specified: write the entire match.
        return matcher.group();
    }
}
