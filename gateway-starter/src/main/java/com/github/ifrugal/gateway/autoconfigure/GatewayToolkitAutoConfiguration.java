package com.github.ifrugal.gateway.autoconfigure;

import com.github.ifrugal.gateway.core.cache.CacheProvider;
import com.github.ifrugal.gateway.core.cache.CaffeineProvider;
import com.github.ifrugal.gateway.core.cache.NoOpCacheProvider;
import com.github.ifrugal.gateway.core.config.CachingProperties;
import com.github.ifrugal.gateway.core.config.CorsProperties;
import com.github.ifrugal.gateway.core.config.LoggingProperties;
import com.github.ifrugal.gateway.core.config.RequestIdProperties;
import com.github.ifrugal.gateway.core.config.SecurityProperties;
import com.github.ifrugal.gateway.core.conman.ConmanCache;
import com.github.ifrugal.gateway.core.conman.ConmanProperties;
import com.github.ifrugal.gateway.core.conman.ConmanHandler;
import com.github.ifrugal.gateway.core.controller.CacheController;
import com.github.ifrugal.gateway.core.conman.ConmanAdminController;
import com.github.ifrugal.gateway.core.filter.LoggingAndCachingWebFilter;
import com.github.ifrugal.gateway.core.filter.RequestIdThreadLocalAccessor;
import io.micrometer.context.ContextRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.reactive.function.server.*;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Hooks;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Auto-configuration for the Spring Gateway Toolkit.
 *
 * <p>This class automatically configures:</p>
 * <ul>
 *   <li>Request ids: validation, forwarding, response echo and MDC propagation (gateway.request-id.*)</li>
 *   <li>Request/Response logging (when gateway.logging.enabled=true)</li>
 *   <li>Response caching with Caffeine (when gateway.caching.enabled=true)</li>
 *   <li>Conman mock API framework (when gateway.conman.enabled=true)</li>
 *   <li>CORS configuration (when gateway.cors.enabled=true)</li>
 * </ul>
 */
@AutoConfiguration
@EnableConfigurationProperties({
        LoggingProperties.class,
        CachingProperties.class,
        CorsProperties.class,
        SecurityProperties.class,
        ConmanProperties.class,
        RequestIdProperties.class
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
            CacheProvider cacheProvider,
            RequestIdProperties requestIdProperties) {
        log.info("Configuring LoggingAndCachingWebFilter (logging={}, caching={}, requestIdHeader={})",
                loggingProperties.isEnabled(), cachingProperties.isEnabled(), requestIdProperties.getHeaderName());
        return new LoggingAndCachingWebFilter(loggingProperties, cachingProperties, cacheProvider,
                loggingProperties.getIgnorePaths(), requestIdProperties);
    }

    /**
     * Configure CORS filter.
     *
     * <p>The request-id header is always echoed, so it is added to the exposed headers
     * automatically (union with {@code gateway.cors.exposed-headers}) to let browsers read it.</p>
     */
    @Bean
    @ConditionalOnProperty(prefix = "gateway.cors", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(name = "gatewayCorsFilter")
    public WebFilter gatewayCorsFilter(CorsProperties corsProperties, RequestIdProperties requestIdProperties) {
        log.info("Configuring CORS filter with origins: {}", corsProperties.getAllowedOrigins());

        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(corsProperties.getAllowedOrigins());
        config.setAllowedMethods(corsProperties.getAllowedMethods());
        config.setAllowedHeaders(corsProperties.getAllowedHeaders());
        config.setExposedHeaders(exposedHeadersWithRequestId(
                corsProperties.getExposedHeaders(), requestIdProperties.getHeaderName()));
        config.setMaxAge(corsProperties.getMaxAge());
        config.setAllowCredentials(corsProperties.isAllowCredentials());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        return new CorsWebFilter(source);
    }

    /**
     * Union of the configured exposed headers and the request-id header, keeping the first
     * spelling of each name and dropping case-insensitive duplicates.
     */
    static List<String> exposedHeadersWithRequestId(List<String> configured, String requestIdHeader) {
        List<String> result = new ArrayList<>();
        Set<String> seen = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (configured != null) {
            for (String header : configured) {
                if (header != null && !header.isBlank() && seen.add(header.trim())) {
                    result.add(header.trim());
                }
            }
        }
        if (requestIdHeader != null && seen.add(requestIdHeader.trim())) {
            result.add(requestIdHeader.trim());
        }
        return result;
    }

    /**
     * Bridges the request id from the Reactor Context to the SLF4J MDC.
     *
     * <p>Registers a {@link RequestIdThreadLocalAccessor} with the global Micrometer
     * {@link ContextRegistry} (an accessor with the same key replaces any earlier one) and calls
     * {@link Hooks#enableAutomaticContextPropagation()}, so Reactor restores the MDC entry on
     * every operator, subscription and scheduler hop of a request and clears it afterwards.</p>
     *
     * <p>Interaction with Spring Boot: {@code spring.reactor.context-propagation=auto} makes
     * Boot's {@code ReactorAutoConfiguration} call the same Reactor hook; the default
     * ({@code limited}) does not. The hook is idempotent (keyed registration), so enabling both
     * does no double work and does not conflict. Setting
     * {@code gateway.request-id.context-propagation=false} skips this configuration entirely; the
     * id is then still available as an exchange attribute and in the Reactor Context, and Boot's
     * {@code auto} mode alone does not populate the MDC key because no accessor is registered.
     * Automatic propagation is a JVM-wide Reactor setting and stays on until the JVM exits.</p>
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({ContextRegistry.class, Hooks.class})
    @ConditionalOnProperty(prefix = "gateway.request-id", name = "context-propagation",
            havingValue = "true", matchIfMissing = true)
    public static class RequestIdContextPropagationConfiguration {

        @Bean
        public RequestIdThreadLocalAccessor requestIdThreadLocalAccessor(RequestIdProperties requestIdProperties) {
            RequestIdThreadLocalAccessor accessor = new RequestIdThreadLocalAccessor(requestIdProperties.getMdcKey());
            ContextRegistry.getInstance().registerThreadLocalAccessor(accessor);
            Hooks.enableAutomaticContextPropagation();
            log.info("Enabled Reactor automatic context propagation of request ids to MDC key '{}'",
                    accessor.getMdcKey());
            return accessor;
        }
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
