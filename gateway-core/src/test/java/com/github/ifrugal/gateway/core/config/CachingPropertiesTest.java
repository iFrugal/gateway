package com.github.ifrugal.gateway.core.config;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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

    // --- JSR-380 validation -------------------------------------------------

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void initValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeFactory() {
        factory.close();
    }

    @Test
    @DisplayName("validation should reject zero max-size")
    void rejectsZeroMaxSize() {
        CachingProperties props = new CachingProperties();
        props.setMaxSize(0);

        Set<ConstraintViolation<CachingProperties>> violations = validator.validate(props);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("maxSize");
    }

    @Test
    @DisplayName("validation should reject negative default-ttl")
    void rejectsNegativeDefaultTtl() {
        CachingProperties props = new CachingProperties();
        props.setDefaultTtl(-5);

        Set<ConstraintViolation<CachingProperties>> violations = validator.validate(props);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("defaultTtl");
    }

    @Test
    @DisplayName("validation should reject blank provider")
    void rejectsBlankProvider() {
        CachingProperties props = new CachingProperties();
        props.setProvider("   ");

        Set<ConstraintViolation<CachingProperties>> violations = validator.validate(props);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("provider");
    }

    @Test
    @DisplayName("validation should propagate to nested rule TTL")
    void rejectsNonPositiveRuleTtl() {
        CachingProperties.CacheRuleConfig rule = new CachingProperties.CacheRuleConfig();
        rule.setPaths(List.of("/api/foo"));
        rule.setMethods(List.of("GET"));
        rule.setTtl(0);  // not @Positive

        CachingProperties props = new CachingProperties();
        props.setRules(List.of(rule));

        Set<ConstraintViolation<CachingProperties>> violations = validator.validate(props);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .anyMatch(p -> p.endsWith(".ttl"));
    }

    @Test
    @DisplayName("validation should pass on a sensible default config")
    void acceptsDefaults() {
        Set<ConstraintViolation<CachingProperties>> violations = validator.validate(new CachingProperties());
        assertThat(violations).isEmpty();
    }
}
