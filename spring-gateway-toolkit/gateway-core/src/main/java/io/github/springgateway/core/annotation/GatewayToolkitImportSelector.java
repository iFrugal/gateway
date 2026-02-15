package io.github.springgateway.core.annotation;

import org.springframework.context.annotation.ImportSelector;
import org.springframework.core.type.AnnotationMetadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Import selector that conditionally imports gateway toolkit configurations
 * based on the EnableGatewayToolkit annotation attributes.
 */
public class GatewayToolkitImportSelector implements ImportSelector {

    @Override
    public String[] selectImports(AnnotationMetadata importingClassMetadata) {
        Map<String, Object> attributes = importingClassMetadata
                .getAnnotationAttributes(EnableGatewayToolkit.class.getName());

        List<String> imports = new ArrayList<>();

        // Always import the base configuration
        imports.add("io.github.springgateway.autoconfigure.GatewayToolkitAutoConfiguration");

        return imports.toArray(new String[0]);
    }
}
