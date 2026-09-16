package com.github.ifrugal.gateway.autoconfigure;

import com.github.ifrugal.gateway.core.cache.CacheProvider;
import com.github.ifrugal.gateway.core.cache.CaffeineProvider;
import com.github.ifrugal.gateway.core.cache.NoOpCacheProvider;
import com.github.ifrugal.gateway.core.config.CachingProperties;
import com.github.ifrugal.gateway.core.config.CorsProperties;
import com.github.ifrugal.gateway.core.config.LoggingProperties;
import com.github.ifrugal.gateway.core.config.SecurityProperties;
import com.github.ifrugal.gateway.core.conman.ConmanCache;
import com.github.ifrugal.gateway.core.conman.ConmanProperties;
import com.github.ifrugal.gateway.core.conman.ConmanHandler;
import com.github.ifrugal.gateway.core.controller.CacheController;
import com.github.ifrugal.gateway.core.conman.ConmanAdminController;
import com.github.ifrugal.gateway.core.filter.LoggingAndCachingWebFilter;
import com.github.ifrugal.gateway.core.filter.OrderedCorsWebFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.reactive.function.server.*;
import org.springframework.web.server.WebFilter;

/**
 * Auto-configuration for the Spring Gateway Toolkit.
 *
 * <p>This class automatically configures:</p>
 * <ul>
 *   <li>Request/Response logging (when gateway.logging.enabled=true)</li>
 *   <li>Response caching with Caffeine (when gateway.caching.enabled=true)</li>
 *   <li>Conman mock API framework (when gateway.conman.enabled=true)</li>
 *   <li>CORS policy and filter (when gateway.cors.enabled=true, the default), with the filter
 *       ordered ahead of Spring Security so 401/403 responses carry CORS headers too</li>
 * </ul>
 */
@AutoConfiguration
@EnableConfigurationProperties({
        LoggingProperties.class,
        CachingProperties.class,
        CorsProperties.class,
        SecurityProperties.class,
        ConmanProperties.class
})
@Import({CacheController.class, ConmanAdminController.class})
@Slf4j
public class GatewayToolkitAutoConfiguration {

    /**
     * Configure the Caffeine cache provider when caching is enabled.
     */
    @Bean
    @ConditionalOnMissingBean(CacheProvider.class)
    @ConditionalOnProperty(prefix = "gateway.caching", name = "enabled", havingValue = "true")
    public CacheProvider caffeineProvider(CachingProperties cachingProperties) {
        log.info("Configuring Caffeine cache provider with maxSize={}, defaultTtl={}s",
                cachingProperties.getMaxSize(), cachingProperties.getDefaultTtl());
        return new CaffeineProvider(cachingProperties);
    }

    /**
     * No-op cache provider when caching is disabled.
     */
    @Bean
    @ConditionalOnMissingBean(CacheProvider.class)
    public CacheProvider noOpCacheProvider() {
        log.debug("Caching is disabled, using NoOpCacheProvider");
        return new NoOpCacheProvider();
    }

    /**
     * Configure the logging and caching web filter.
     */
    @Bean
    @ConditionalOnMissingBean(LoggingAndCachingWebFilter.class)
    public LoggingAndCachingWebFilter loggingAndCachingWebFilter(
            LoggingProperties loggingProperties,
            CachingProperties cachingProperties,
            CacheProvider cacheProvider) {
        log.info("Configuring LoggingAndCachingWebFilter (logging={}, caching={})",
                loggingProperties.isEnabled(), cachingProperties.isEnabled());
        return new LoggingAndCachingWebFilter(loggingProperties, cachingProperties, cacheProvider,
                loggingProperties.getIgnorePaths());
    }

    /**
     * The CORS policy declared under {@code gateway.cors.*}, exposed as the reactive
     * {@link CorsConfigurationSource} that both {@link #gatewayCorsFilter} and Spring Security's
     * {@code ServerHttpSecurity.cors()} consume.
     *
     * <p>Spring Security's WebFlux DSL looks up a single {@code CorsConfigurationSource} bean on
     * its own, so every {@code SecurityWebFilterChain} in the application that has not called
     * {@code cors(CorsSpec::disable)}, including a chain that replaces the toolkit's, applies
     * this policy to the responses it writes itself (401, 403). The bundled filter ahead of the
     * chain is what makes that belt-and-braces; see {@link OrderedCorsWebFilter}.
     *
     * <p>Declare your own {@code CorsConfigurationSource} bean to change the policy: this one
     * steps aside, and the bundled filter and the security chain both use yours.
     */
    @Bean
    @ConditionalOnProperty(prefix = "gateway.cors", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(CorsConfigurationSource.class)
    public CorsConfigurationSource gatewayCorsConfigurationSource(CorsProperties corsProperties) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(corsProperties.getAllowedOrigins());
        config.setAllowedMethods(corsProperties.getAllowedMethods());
        config.setAllowedHeaders(corsProperties.getAllowedHeaders());
        config.setExposedHeaders(corsProperties.getExposedHeaders());
        config.setMaxAge(corsProperties.getMaxAge());
        config.setAllowCredentials(corsProperties.isAllowCredentials());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    /**
     * The CORS filter, as an {@link OrderedCorsWebFilter} rather than a plain
     * {@code CorsWebFilter}. A plain {@code CorsWebFilter} is not {@code Ordered} and lands at
     * lowest precedence, behind Spring Security's {@code WebFilterChainProxy} (order -100); a 401
     * or 403 the security chain writes itself then never gets its CORS headers and the browser
     * reports a CORS error instead of the real status. At {@link OrderedCorsWebFilter#ORDER} the
     * headers are on the response before the security chain runs, and a preflight is answered
     * before it needs any {@code permitAll()} rule.
     */
    @Bean
    @ConditionalOnProperty(prefix = "gateway.cors", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(name = "gatewayCorsFilter")
    public WebFilter gatewayCorsFilter(CorsProperties corsProperties,
                                      CorsConfigurationSource corsConfigurationSource) {
        log.info("Configuring CORS filter at order {} (ahead of Spring Security) with origins: {}",
                OrderedCorsWebFilter.ORDER, corsProperties.getAllowedOrigins());
        return new OrderedCorsWebFilter(corsConfigurationSource);
    }

    /**
     * Conman configuration - enabled when gateway.conman.enabled=true.
     */
    @Configuration
    @ConditionalOnProperty(prefix = "gateway.conman", name = "enabled", havingValue = "true")
    public static class ConmanAutoConfiguration {

        @Bean
        @ConditionalOnMissingBean(ConmanCache.class)
        public ConmanCache conmanCache(ConmanProperties conmanProperties, ApplicationContext applicationContext) {
            log.info("Configuring Conman mock API framework with mappings: {}",
                    conmanProperties.getServletUriMappings());
            return new ConmanCache(conmanProperties, applicationContext);
        }

        @Bean
        @ConditionalOnMissingBean(ConmanHandler.class)
        public ConmanHandler conmanHandler(ConmanCache conmanCache, ConmanProperties conmanProperties) {
            return new ConmanHandler(conmanCache, conmanProperties);
        }

        /**
         * Register Conman routes using the managed ConmanHandler bean.
         * Reuses the same ConmanHandler instance registered as a Spring bean.
         */
        @Bean
        public RouterFunction<ServerResponse> conmanRoutes(ConmanProperties conmanProperties,
                                                           ConmanHandler conmanHandler) {
            HandlerFunction<ServerResponse> handlerFunction = conmanHandler::service;

            if (conmanProperties.getServletUriMappings().isEmpty()) {
                log.warn("No Conman servlet URI mappings configured");
                return RouterFunctions.route(RequestPredicates.path("/mock/**"), handlerFunction);
            }

            RouterFunction<ServerResponse> routerFunction = RouterFunctions.route(
                    RequestPredicates.path(conmanProperties.getServletUriMappings().get(0)), handlerFunction
            );

            for (int i = 1; i < conmanProperties.getServletUriMappings().size(); i++) {
                routerFunction = routerFunction.andRoute(
                        RequestPredicates.path(conmanProperties.getServletUriMappings().get(i)), handlerFunction
                );
            }

            return routerFunction;
        }
    }
}
