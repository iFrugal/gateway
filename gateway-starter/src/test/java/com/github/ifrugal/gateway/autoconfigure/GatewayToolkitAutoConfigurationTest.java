package com.github.ifrugal.gateway.autoconfigure;

import com.github.ifrugal.gateway.core.cache.CacheProvider;
import com.github.ifrugal.gateway.core.cache.CaffeineProvider;
import com.github.ifrugal.gateway.core.cache.NoOpCacheProvider;
import com.github.ifrugal.gateway.core.filter.LoggingAndCachingWebFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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
}
