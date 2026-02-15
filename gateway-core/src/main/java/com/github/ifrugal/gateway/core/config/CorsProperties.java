package com.github.ifrugal.gateway.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration properties for CORS settings.
 *
 * Example configuration:
 * <pre>
 * gateway:
 *   cors:
 *     enabled: true
 *     allowed-origins: ["http://localhost:3000", "https://myapp.com"]
 *     allowed-methods: ["GET", "POST", "PUT", "DELETE", "OPTIONS"]
 *     allowed-headers: ["*"]
 *     max-age: 3600
 *     allow-credentials: true
 * </pre>
 */
@ConfigurationProperties(prefix = "gateway.cors")
@Data
public class CorsProperties {

    /**
     * Enable or disable CORS configuration.
     */
    private boolean enabled = true;

    /**
     * List of allowed origins.
     */
    private List<String> allowedOrigins = new ArrayList<>();

    /**
     * List of allowed HTTP methods.
     */
    private List<String> allowedMethods = List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");

    /**
     * List of allowed headers.
     */
    private List<String> allowedHeaders = List.of("*");

    /**
     * List of exposed headers.
     */
    private List<String> exposedHeaders = new ArrayList<>();

    /**
     * Max age in seconds for preflight cache.
     */
    private long maxAge = 3600;

    /**
     * Whether to allow credentials.
     */
    private boolean allowCredentials = true;
}
