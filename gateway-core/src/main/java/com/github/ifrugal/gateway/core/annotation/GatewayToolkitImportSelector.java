package com.github.ifrugal.gateway.core.annotation;

import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.ImportSelector;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.type.AnnotationMetadata;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Import selector that conditionally imports gateway toolkit configurations
 * based on the {@link EnableGatewayToolkit} annotation attributes.
 *
 * <p>This selector reads the annotation attributes (enableLogging, enableCaching, enableConman)
 * and registers them as a low-priority property source in the Spring {@link Environment}.
 * Explicit YAML or environment properties take precedence over annotation defaults.</p>
 *
 * <p>This approach avoids polluting the global JVM state via {@code System.setProperty()},
 * making it safe for multi-context testing and embedded scenarios.</p>
 */
public class GatewayToolkitImportSelector implements ImportSelector, EnvironmentAware {

    private ConfigurableEnvironment environment;

    @Override
    public void setEnvironment(Environment environment) {
        if (environment instanceof ConfigurableEnvironment) {
            this.environment = (ConfigurableEnvironment) environment;
        }
    }

    @Override
    public String[] selectImports(AnnotationMetadata importingClassMetadata) {
        Map<String, Object> attributes = importingClassMetadata
                .getAnnotationAttributes(EnableGatewayToolkit.class.getName());

        List<String> imports = new ArrayList<>();

        // Always import the base configuration
        imports.add("com.github.ifrugal.gateway.autoconfigure.GatewayToolkitAutoConfiguration");

        if (attributes != null && environment != null) {
            // Register annotation attributes as a low-priority property source.
            // This ensures YAML/env properties take precedence (they appear earlier
            // in the property source list), while annotation values act as defaults.
            Map<String, Object> annotationProperties = new HashMap<>();

            boolean enableLogging = (boolean) attributes.getOrDefault("enableLogging", true);
            boolean enableCaching = (boolean) attributes.getOrDefault("enableCaching", true);
            boolean enableConman = (boolean) attributes.getOrDefault("enableConman", true);

            if (!enableLogging) {
                annotationProperties.put("gateway.logging.enabled", "false");
            }
            if (!enableCaching) {
                annotationProperties.put("gateway.caching.enabled", "false");
            }
            if (!enableConman) {
                annotationProperties.put("gateway.conman.enabled", "false");
            }

            if (!annotationProperties.isEmpty()) {
                environment.getPropertySources().addLast(
                        new MapPropertySource("gatewayToolkitAnnotation", annotationProperties));
            }
        }

        return imports.toArray(new String[0]);
    }
}
