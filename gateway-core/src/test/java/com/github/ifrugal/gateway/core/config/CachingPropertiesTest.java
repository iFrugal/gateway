package com.github.ifrugal.gateway.core.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CachingProperties")
class CachingPropertiesTest {

    @Test
    @DisplayName("should have sensible defaults")
    void defaults() {
        CachingProperties props = new CachingProperties();
        assertThat(props.isEnabled()).isFalse();
        assertThat(props.getProvider()).isEqualTo("caffeine");
        assertThat(props.getDefaultTtl()).isEqualTo(86400);
        assertThat(props.getMaxSize()).isEqualTo(10000);
        assertThat(props.getRules()).isEmpty();
    }

    @Test
    @DisplayName("CacheRuleConfig should parse HTTP methods correctly")
    void cacheRuleHttpMethods() {
        CachingProperties.CacheRuleConfig rule = new CachingProperties.CacheRuleConfig();
        rule.setMethods(List.of("GET", "POST"));

        Set<HttpMethod> methods = rule.getHttpMethods();
        assertThat(methods).containsExactlyInAnyOrder(HttpMethod.GET, HttpMethod.POST);
        assertThat(rule.isWildcardMethod()).isFalse();
    }

    @Test
    @DisplayName("CacheRuleConfig should detect wildcard method")
    void cacheRuleWildcard() {
        CachingProperties.CacheRuleConfig rule = new CachingProperties.CacheRuleConfig();
        rule.setMethods(List.of("*"));

        assertThat(rule.isWildcardMethod()).isTrue();
        assertThat(rule.getHttpMethods()).isEmpty();
    }
}
