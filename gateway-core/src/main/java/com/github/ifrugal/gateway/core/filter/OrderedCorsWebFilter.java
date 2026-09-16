package com.github.ifrugal.gateway.core.filter;

import org.springframework.core.Ordered;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.CorsProcessor;
import org.springframework.web.cors.reactive.CorsWebFilter;

/**
 * A {@link CorsWebFilter} that declares its position in the {@code WebFilter} chain.
 *
 * <p>{@code CorsWebFilter} implements {@code WebFilter} but not {@link Ordered}, so a bean of that
 * type is sorted at {@link Ordered#LOWEST_PRECEDENCE}: behind Spring Security's
 * {@code WebFilterChainProxy}, which Spring Security registers at order
 * {@value #SPRING_SECURITY_FILTER_ORDER}. Every response the security chain writes itself (a 401
 * for a missing or expired token, a 403 from an authorisation rule) is then committed before the
 * CORS filter ever runs, and goes back to the browser without {@code Access-Control-Allow-Origin}.
 * The browser surfaces that as a CORS failure rather than as a 401, so a single-page app cannot
 * tell an expired token from an outage and never gets the chance to renew it.
 *
 * <p>This subclass runs at {@link #ORDER} by default, ahead of the security chain.
 * {@code CorsWebFilter} writes the CORS response headers <em>before</em> delegating, and answers a
 * preflight without delegating at all, so every response, including those written by Spring
 * Security, carries the headers, and a preflight never reaches authentication.
 *
 * <p>Order of the toolkit's filters relative to Spring Security, first to run first:
 * <ol>
 *   <li>{@link LoggingAndCachingWebFilter} at {@link Ordered#HIGHEST_PRECEDENCE}, so it sees the
 *       untouched request and the final response</li>
 *   <li>this filter at {@value #ORDER}</li>
 *   <li>Spring Security's {@code WebFilterChainProxy} at {@value #SPRING_SECURITY_FILTER_ORDER}</li>
 * </ol>
 */
public class OrderedCorsWebFilter extends CorsWebFilter implements Ordered {

    /**
     * The order Spring Security gives its {@code WebFilterChainProxy}
     * ({@code WebFluxSecurityConfiguration.WEB_FILTER_CHAIN_FILTER_ORDER}). Duplicated rather than
     * referenced because spring-security is an optional dependency of the toolkit and this class
     * must load without it.
     */
    public static final int SPRING_SECURITY_FILTER_ORDER = -100;

    /** Default order: ahead of Spring Security, behind {@link LoggingAndCachingWebFilter}. */
    public static final int ORDER = SPRING_SECURITY_FILTER_ORDER - 100;

    private final int order;

    /**
     * A filter for the given policy at the default {@link #ORDER}.
     *
     * @param configSource where the CORS policy for a request comes from
     */
    public OrderedCorsWebFilter(CorsConfigurationSource configSource) {
        this(configSource, ORDER);
    }

    /**
     * A filter for the given policy at a custom order. Anything below
     * {@value #SPRING_SECURITY_FILTER_ORDER} keeps it ahead of Spring Security.
     *
     * @param configSource where the CORS policy for a request comes from
     * @param order        the {@link Ordered} value for this filter
     */
    public OrderedCorsWebFilter(CorsConfigurationSource configSource, int order) {
        super(configSource);
        this.order = order;
    }

    /**
     * A filter with a custom {@link CorsProcessor} at a custom order.
     *
     * @param configSource where the CORS policy for a request comes from
     * @param processor    applies the policy to the exchange
     * @param order        the {@link Ordered} value for this filter
     */
    public OrderedCorsWebFilter(CorsConfigurationSource configSource, CorsProcessor processor, int order) {
        super(configSource, processor);
        this.order = order;
    }

    @Override
    public int getOrder() {
        return order;
    }
}
