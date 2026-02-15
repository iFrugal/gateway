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
import com.github.ifrugal.gateway.core.conman.ConmanServlet;
import com.github.ifrugal.gateway.core.controller.CacheController;
import com.github.ifrugal.gateway.core.conman.ConmanAdminController;
import com.github.ifrugal.gateway.core.filter.LoggingAndCachingWebFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.reactive.function.server.*;
import org.springframework.web.server.WebFilter;

/**
 * Auto-configuration for the Spring Gateway Toolkit.
 *
 * This class automatically configures:
 * - Request/Response logging (when gateway.logging.enabled=true)
 * - Response caching with Caffeine (when gateway.caching.enabled=true)
 * - Conman mock API framework (when gateway.conman.enabled=true)
 * - CORS configuration (when gateway.cors.enabled=true)
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
     * Configure the cache provider.
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
        return new LoggingAndCachingWebFilter(loggingProperties, cachingProperties, cacheProvider);
    }

    /**
     * Configure CORS filter.
     */
    @Bean
    @ConditionalOnProperty(prefix = "gateway.cors", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(name = "gatewayCorsFilter")
    public WebFilter gatewayCorsFilter(CorsProperties corsProperties) {
        log.info("Configuring CORS filter with origins: {}", corsProperties.getAllowedOrigins());

        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(corsProperties.getAllowedOrigins());
        config.setAllowedMethods(corsProperties.getAllowedMethods());
        config.setAllowedHeaders(corsProperties.getAllowedHeaders());
        config.setExposedHeaders(corsProperties.getExposedHeaders());
        config.setMaxAge(corsProperties.getMaxAge());
        config.setAllowCredentials(corsProperties.isAllowCredentials());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        return new CorsWebFilter(source);
    }

    /**
     * Conman configuration - enabled when gateway.conman.enabled=true
     */
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
        @ConditionalOnMissingBean(ConmanServlet.class)
        public ConmanServlet conmanServlet(ConmanCache conmanCache) {
            return new ConmanServlet(conmanCache);
        }

        @Bean
        public RouterFunction<ServerResponse> conmanRoutes(ConmanProperties conmanProperties, ConmanCache conmanCache) {
            ConmanServlet conmanServlet = new ConmanServlet(conmanCache);
            HandlerFunction<ServerResponse> handlerFunction = conmanServlet::service;

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
