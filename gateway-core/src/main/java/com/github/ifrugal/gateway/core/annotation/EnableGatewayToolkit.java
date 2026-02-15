package com.github.ifrugal.gateway.core.annotation;

import org.springframework.context.annotation.Import;

import java.lang.annotation.*;

/**
 * Enable the Spring Gateway Toolkit features.
 *
 * This annotation enables:
 * - Request/Response logging
 * - Response caching with Caffeine
 * - Conman mock API framework
 * - CORS configuration
 *
 * All features are configurable via application properties under the 'gateway' prefix.
 *
 * Example usage:
 * <pre>
 * &#064;SpringBootApplication
 * &#064;EnableGatewayToolkit
 * public class MyGatewayApplication {
 *     public static void main(String[] args) {
 *         SpringApplication.run(MyGatewayApplication.class, args);
 *     }
 * }
 * </pre>
 *
 * @see com.github.ifrugal.gateway.core.config.LoggingProperties
 * @see com.github.ifrugal.gateway.core.config.CachingProperties
 * @see com.github.ifrugal.gateway.core.conman.ConmanProperties
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(GatewayToolkitImportSelector.class)
public @interface EnableGatewayToolkit {

    /**
     * Enable request/response logging.
     */
    boolean enableLogging() default true;

    /**
     * Enable response caching.
     */
    boolean enableCaching() default true;

    /**
     * Enable Conman mock API framework.
     */
    boolean enableConman() default true;
}
