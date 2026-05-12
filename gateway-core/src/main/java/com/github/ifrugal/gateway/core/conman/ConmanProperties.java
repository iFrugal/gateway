package com.github.ifrugal.gateway.core.conman;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Configuration properties for the Conman mock API framework.
 *
 * <p>Example configuration:
 * <pre>
 * gateway:
 *   conman:
 *     enabled: true
 *     servlet-uri-mappings:
 *       - /mock/**
 *     mapping-files:
 *       - classpath:conman.yml
 *       - classpath:conman/api-mocks.yml
 *     banner-path: classpath:conman-banner.txt
 *     tenant-id-header: tenant-id
 * </pre>
 */
@ConfigurationProperties(prefix = "gateway.conman")
@Validated
@Data
public class ConmanProperties {

    /** Default request header consulted by {@link ConmanServlet} for tenant resolution. */
    public static final String DEFAULT_TENANT_ID_HEADER = "tenant-id";

    /**
     * Enable or disable the Conman mock API framework.
     */
    private boolean enabled = false;

    /**
     * URI patterns to handle with the mock handler.
     */
    @NotEmpty(message = "gateway.conman.servlet-uri-mappings must not be empty when Conman is enabled")
    private List<String> servletUriMappings = Arrays.asList("/mock/**");

    /**
     * List of YAML files containing mock configurations.
     */
    @NotEmpty(message = "gateway.conman.mapping-files must list at least one file")
    private List<String> mappingFiles = new ArrayList<>(Arrays.asList("classpath:conman.yml"));

    /**
     * Path to the Conman banner file.
     */
    private String bannerPath = "classpath:conman-banner.txt";

    /**
     * Name of the request header used to identify the tenant for mock lookup.
     * Matching is case-insensitive at runtime. Defaults to {@code "tenant-id"};
     * was hardcoded in {@code ConmanServlet} prior to {@code 1.1.0}.
     */
    @NotBlank(message = "gateway.conman.tenant-id-header must not be blank")
    private String tenantIdHeader = DEFAULT_TENANT_ID_HEADER;
}
