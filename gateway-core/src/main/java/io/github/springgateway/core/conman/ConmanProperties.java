package io.github.springgateway.core.conman;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Configuration properties for the Conman mock API framework.
 *
 * Example configuration:
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
 * </pre>
 */
@ConfigurationProperties(prefix = "gateway.conman")
@Data
public class ConmanProperties {

    /**
     * Enable or disable the Conman mock API framework.
     */
    private boolean enabled = false;

    /**
     * URI patterns to handle with mock servlet.
     */
    private List<String> servletUriMappings = Arrays.asList("/mock/**");

    /**
     * List of YAML files containing mock configurations.
     */
    private List<String> mappingFiles = new ArrayList<>(Arrays.asList("classpath:conman.yml"));

    /**
     * Path to the Conman banner file.
     */
    private String bannerPath = "classpath:conman-banner.txt";
}
