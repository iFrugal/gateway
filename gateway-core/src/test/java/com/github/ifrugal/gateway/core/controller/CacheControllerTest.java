package com.github.ifrugal.gateway.core.controller;

import com.github.ifrugal.gateway.core.cache.CacheProvider;
import com.github.ifrugal.gateway.core.cache.CaffeineProvider;
import com.github.ifrugal.gateway.core.config.CachingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CacheController")
class CacheControllerTest {

    private CacheController controller;
    private CacheProvider cacheProvider;

    @BeforeEach
    void setUp() {
        CachingProperties props = new CachingProperties();
        props.setMaxSize(100);
        props.setDefaultTtl(3600);
        cacheProvider = new CaffeineProvider(props);
        controller = new CacheController(cacheProvider);
    }

    @Test
    @DisplayName("should put and get cached value")
    void putAndGet() {
        // Put value
        StepVerifier.create(controller.putInCache("testKey", "testValue", 300))
                .assertNext(result -> {
                    assertThat(result.get("status")).isEqualTo("success");
                    assertThat(result.get("key")).isEqualTo("testKey");
                })
                .verifyComplete();

        // Get value
        StepVerifier.create(controller.getFromCache("testKey"))
                .assertNext(value -> assertThat(value).isEqualTo("testValue"))
                .verifyComplete();
    }

    @Test
    @DisplayName("should return error for non-existent key")
    void getNotFound() {
        StepVerifier.create(controller.getFromCache("nonexistent"))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    @DisplayName("should invalidate a cached key")
    void invalidateKey() {
        cacheProvider.put("deleteMe", "value", 300).block();

        StepVerifier.create(controller.invalidateKey("deleteMe"))
                .assertNext(result -> assertThat(result.get("status")).isEqualTo("success"))
                .verifyComplete();

        // Verify it's gone
        StepVerifier.create(controller.getFromCache("deleteMe"))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    @DisplayName("should list all cached keys")
    void listKeys() {
        cacheProvider.put("k1", "v1", 300).block();
        cacheProvider.put("k2", "v2", 300).block();

        Map<String, Object> keys = controller.listKeys();
        assertThat(keys).containsKey("k1");
        assertThat(keys).containsKey("k2");
    }

    @Test
    @DisplayName("should clear all cached entries")
    void clearAll() {
        cacheProvider.put("k1", "v1", 300).block();

        StepVerifier.create(controller.clearAll())
                .assertNext(result -> assertThat(result.get("status")).isEqualTo("success"))
                .verifyComplete();

        assertThat(controller.listKeys()).isEmpty();
    }
}
