package com.github.ifrugal.gateway.core.annotation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.type.AnnotationMetadata;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("GatewayToolkitImportSelector")
class GatewayToolkitImportSelectorTest {

    @Test
    @DisplayName("should always import GatewayToolkitAutoConfiguration")
    void alwaysImportsAutoConfiguration() {
        GatewayToolkitImportSelector selector = new GatewayToolkitImportSelector();
        selector.setEnvironment(new StandardEnvironment());

        AnnotationMetadata metadata = mockMetadata(true, true, true);

        String[] imports = selector.selectImports(metadata);

        assertThat(imports).contains(
                "com.github.ifrugal.gateway.autoconfigure.GatewayToolkitAutoConfiguration");
    }

    @Test
    @DisplayName("should register environment properties when features are disabled")
    void registersPropertiesWhenDisabled() {
        ConfigurableEnvironment env = new StandardEnvironment();
        GatewayToolkitImportSelector selector = new GatewayToolkitImportSelector();
        selector.setEnvironment(env);

        AnnotationMetadata metadata = mockMetadata(false, false, false);

        selector.selectImports(metadata);

        // Properties should be registered in the Spring Environment
        assertThat(env.getProperty("gateway.logging.enabled")).isEqualTo("false");
        assertThat(env.getProperty("gateway.caching.enabled")).isEqualTo("false");
        assertThat(env.getProperty("gateway.conman.enabled")).isEqualTo("false");
    }

    @Test
    @DisplayName("should not pollute System properties (unlike previous implementation)")
    void doesNotSetSystemProperties() {
        ConfigurableEnvironment env = new StandardEnvironment();
        GatewayToolkitImportSelector selector = new GatewayToolkitImportSelector();
        selector.setEnvironment(env);

        // Clean slate - ensure no system properties
        System.clearProperty("gateway.logging.enabled");
        System.clearProperty("gateway.caching.enabled");
        System.clearProperty("gateway.conman.enabled");

        AnnotationMetadata metadata = mockMetadata(false, false, false);
        selector.selectImports(metadata);

        // System properties should NOT be set
        assertThat(System.getProperty("gateway.logging.enabled")).isNull();
        assertThat(System.getProperty("gateway.caching.enabled")).isNull();
        assertThat(System.getProperty("gateway.conman.enabled")).isNull();
    }

    @Test
    @DisplayName("should not register properties when all features are enabled (defaults)")
    void noPropertiesWhenAllEnabled() {
        ConfigurableEnvironment env = new StandardEnvironment();
        MutablePropertySources propertySources = env.getPropertySources();
        int sourceCountBefore = propertySources.size();

        GatewayToolkitImportSelector selector = new GatewayToolkitImportSelector();
        selector.setEnvironment(env);

        AnnotationMetadata metadata = mockMetadata(true, true, true);

        selector.selectImports(metadata);

        // No additional property source should be added when all features are enabled
        assertThat(propertySources.size()).isEqualTo(sourceCountBefore);
    }

    @Test
    @DisplayName("should handle null annotation attributes gracefully")
    void handlesNullAttributes() {
        GatewayToolkitImportSelector selector = new GatewayToolkitImportSelector();
        selector.setEnvironment(new StandardEnvironment());

        AnnotationMetadata metadata = mock(AnnotationMetadata.class);
        when(metadata.getAnnotationAttributes(EnableGatewayToolkit.class.getName()))
                .thenReturn(null);

        String[] imports = selector.selectImports(metadata);

        assertThat(imports).hasSize(1);
        assertThat(imports[0]).contains("GatewayToolkitAutoConfiguration");
    }

    @Test
    @DisplayName("should handle non-configurable environment gracefully")
    void handlesNonConfigurableEnvironment() {
        GatewayToolkitImportSelector selector = new GatewayToolkitImportSelector();
        // Don't set any environment — simulates a non-ConfigurableEnvironment scenario

        AnnotationMetadata metadata = mockMetadata(false, false, false);

        String[] imports = selector.selectImports(metadata);

        // Should still return imports without error
        assertThat(imports).hasSize(1);
    }

    @Test
    @DisplayName("annotation properties should have lowest priority (explicit config wins)")
    void propertySourceHasLowestPriority() {
        ConfigurableEnvironment env = new StandardEnvironment();

        // Set an explicit system property that should take precedence
        System.setProperty("gateway.logging.enabled", "true");
        try {
            GatewayToolkitImportSelector selector = new GatewayToolkitImportSelector();
            selector.setEnvironment(env);

            // Annotation says disabled, but system property says enabled
            AnnotationMetadata metadata = mockMetadata(false, true, true);
            selector.selectImports(metadata);

            // System property "true" should take precedence because addLast
            // gives the annotation source the lowest priority
            assertThat(env.getProperty("gateway.logging.enabled")).isEqualTo("true");
        } finally {
            System.clearProperty("gateway.logging.enabled");
        }
    }

    @Test
    @DisplayName("should only register properties for disabled features")
    void onlyRegistersDisabledFeatures() {
        ConfigurableEnvironment env = new StandardEnvironment();
        GatewayToolkitImportSelector selector = new GatewayToolkitImportSelector();
        selector.setEnvironment(env);

        // Only logging disabled
        AnnotationMetadata metadata = mockMetadata(false, true, true);
        selector.selectImports(metadata);

        assertThat(env.getProperty("gateway.logging.enabled")).isEqualTo("false");
        // These should not be in the annotation property source (they're enabled = default)
        assertThat(env.containsProperty("gateway.caching.enabled")).isFalse();
        assertThat(env.containsProperty("gateway.conman.enabled")).isFalse();
    }

    private AnnotationMetadata mockMetadata(boolean logging, boolean caching, boolean conman) {
        AnnotationMetadata metadata = mock(AnnotationMetadata.class);
        when(metadata.getAnnotationAttributes(EnableGatewayToolkit.class.getName()))
                .thenReturn(Map.of(
                        "enableLogging", logging,
                        "enableCaching", caching,
                        "enableConman", conman
                ));
        return metadata;
    }
}
