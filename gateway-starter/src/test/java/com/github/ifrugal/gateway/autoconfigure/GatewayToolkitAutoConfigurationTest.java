package com.github.ifrugal.gateway.autoconfigure;

import com.github.ifrugal.gateway.core.cache.CacheProvider;
import com.github.ifrugal.gateway.core.cache.CaffeineProvider;
import com.github.ifrugal.gateway.core.cache.NoOpCacheProvider;
import com.github.ifrugal.gateway.core.config.RequestIdProperties;
import com.github.ifrugal.gateway.core.filter.LoggingAndCachingWebFilter;
import com.github.ifrugal.gateway.core.filter.RequestIdThreadLocalAccessor;
import io.micrometer.context.ContextRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GatewayToolkitAutoConfiguration")
class GatewayToolkitAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(GatewayToolkitAutoConfiguration.class));

    @Test
    @DisplayName("should register NoOpCacheProvider when caching is disabled")
    void noOpCacheWhenDisabled() {
        contextRunner
                .withPropertyValues("gateway.caching.enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(CacheProvider.class);
                    assertThat(context.getBean(CacheProvider.class)).isInstanceOf(NoOpCacheProvider.class);
                });
    }

    @Test
    @DisplayName("should register CaffeineProvider when caching is enabled")
    void caffeineWhenEnabled() {
        contextRunner
                .withPropertyValues("gateway.caching.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(CacheProvider.class);
                    assertThat(context.getBean(CacheProvider.class)).isInstanceOf(CaffeineProvider.class);
                });
    }

    @Test
    @DisplayName("should register LoggingAndCachingWebFilter")
    void webFilterRegistered() {
        contextRunner
                .run(context -> {
                    assertThat(context).hasSingleBean(LoggingAndCachingWebFilter.class);
                });
    }

    @Test
    @DisplayName("should not register Conman beans when conman is disabled")
    void noConmanWhenDisabled() {
        contextRunner
                .withPropertyValues("gateway.conman.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean("conmanCache");
                    assertThat(context).doesNotHaveBean("conmanServlet");
                });
    }

    @Test
    @DisplayName("should bind gateway.request-id defaults")
    void requestIdPropertyDefaults() {
        contextRunner
                .run(context -> {
                    RequestIdProperties properties = context.getBean(RequestIdProperties.class);
                    assertThat(properties.getHeaderName()).isEqualTo("x-request-id");
                    assertThat(properties.getPattern()).isEqualTo("^[A-Za-z0-9._-]{8,64}$");
                    assertThat(properties.getMdcKey()).isEqualTo("requestId");
                    assertThat(properties.isContextPropagation()).isTrue();
                });
    }

    @Test
    @DisplayName("should bind custom gateway.request-id properties")
    void requestIdPropertiesCustom() {
        contextRunner
                .withPropertyValues(
                        "gateway.request-id.header-name=X-Correlation-Id",
                        "gateway.request-id.pattern=^[a-z]{10}$",
                        "gateway.request-id.mdc-key=correlationId")
                .run(context -> {
                    RequestIdProperties properties = context.getBean(RequestIdProperties.class);
                    assertThat(properties.getHeaderName()).isEqualTo("X-Correlation-Id");
                    assertThat(properties.getPattern()).isEqualTo("^[a-z]{10}$");
                    assertThat(properties.getMdcKey()).isEqualTo("correlationId");
                    assertThat(context.getBean(RequestIdThreadLocalAccessor.class).getMdcKey())
                            .isEqualTo("correlationId");
                });
    }

    @Test
    @DisplayName("should register the MDC accessor with ContextRegistry by default")
    void contextPropagationEnabledByDefault() {
        contextRunner
                .run(context -> {
                    assertThat(context).hasSingleBean(RequestIdThreadLocalAccessor.class);
                    assertThat(ContextRegistry.getInstance().getThreadLocalAccessors())
                            .anyMatch(accessor -> LoggingAndCachingWebFilter.REQUEST_ID_CONTEXT_KEY
                                    .equals(accessor.key()));
                });
    }

    @Test
    @DisplayName("should not register the MDC accessor when context propagation is disabled")
    void contextPropagationDisabled() {
        contextRunner
                .withPropertyValues("gateway.request-id.context-propagation=false")
                .run(context -> assertThat(context).doesNotHaveBean(RequestIdThreadLocalAccessor.class));
    }

    @Test
    @DisplayName("should fail fast on a blank request-id header name")
    void blankHeaderNameRejected() {
        contextRunner
                .withPropertyValues("gateway.request-id.header-name= ")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("should add the request-id header to CORS exposed headers")
    void exposedHeadersAddsRequestId() {
        assertThat(GatewayToolkitAutoConfiguration.exposedHeadersWithRequestId(List.of(), "x-request-id"))
                .containsExactly("x-request-id");
        assertThat(GatewayToolkitAutoConfiguration.exposedHeadersWithRequestId(null, "x-request-id"))
                .containsExactly("x-request-id");
        assertThat(GatewayToolkitAutoConfiguration.exposedHeadersWithRequestId(
                List.of("X-Total-Count"), "x-request-id"))
                .containsExactly("X-Total-Count", "x-request-id");
    }

    @Test
    @DisplayName("should not duplicate exposed headers, ignoring case")
    void exposedHeadersNoDuplicates() {
        assertThat(GatewayToolkitAutoConfiguration.exposedHeadersWithRequestId(
                List.of("X-Request-ID", "X-Total-Count", "x-total-count", " ", "X-Total-Count"), "x-request-id"))
                .containsExactly("X-Request-ID", "X-Total-Count");
    }
}
