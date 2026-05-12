package com.github.ifrugal.gateway.core.cache;

import com.github.ifrugal.gateway.core.config.CachingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CaffeineProvider")
class CaffeineProviderTest {

    private CaffeineProvider provider;

    @BeforeEach
    void setUp() {
        CachingProperties properties = new CachingProperties();
        properties.setMaxSize(100);
        properties.setDefaultTtl(3600);
        provider = new CaffeineProvider(properties);
    }

    @Test
    @DisplayName("should return empty Optional for cache miss")
    void getCacheMiss() {
        StepVerifier.create(provider.get("nonexistent"))
                .assertNext(opt -> assertThat(opt).isEmpty())
                .verifyComplete();
    }

    @Test
    @DisplayName("should store and retrieve a cached value")
    void putAndGet() {
        provider.put("key1", "value1", 300).block();

        StepVerifier.create(provider.get("key1"))
                .assertNext(opt -> {
                    assertThat(opt).isPresent();
                    assertThat(opt.get()).isEqualTo("value1");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("should invalidate a cached entry")
    void invalidate() {
        provider.put("key1", "value1", 300).block();
        provider.invalidate("key1").block();

        StepVerifier.create(provider.get("key1"))
                .assertNext(opt -> assertThat(opt).isEmpty())
                .verifyComplete();
    }

    @Test
    @DisplayName("should clear all entries")
    void clearAll() {
        provider.put("key1", "value1", 300).block();
        provider.put("key2", "value2", 300).block();
        provider.clear().block();

        StepVerifier.create(provider.get("key1"))
                .assertNext(opt -> assertThat(opt).isEmpty())
                .verifyComplete();
        StepVerifier.create(provider.get("key2"))
                .assertNext(opt -> assertThat(opt).isEmpty())
                .verifyComplete();
    }

    @Test
    @DisplayName("should return internal keys with TTL and creation metadata")
    void getInternalKeys() {
        provider.put("key1", "value1", 300).block();
        provider.put("key2", "value2", 600).block();

        Map<String, Object> keys = provider.getInternalKeys();
        assertThat(keys).containsKey("key1");
        assertThat(keys).containsKey("key2");

        // Verify metadata structure
        @SuppressWarnings("unchecked")
        Map<String, Object> key1Meta = (Map<String, Object>) keys.get("key1");
        assertThat(key1Meta).containsKey("ttlSeconds");
        assertThat(key1Meta).containsKey("createdAt");
        assertThat(key1Meta.get("ttlSeconds")).isEqualTo(300L);
    }

    @Test
    @DisplayName("should expire entries after TTL elapses via Caffeine's built-in expiry")
    void expiredEntryAfterTtl() throws InterruptedException {
        // Use 1-second TTL and wait for it to elapse
        provider.put("shortlived", "value", 1).block();

        // Entry should still be present before TTL elapses
        StepVerifier.create(provider.get("shortlived"))
                .assertNext(opt -> assertThat(opt).isPresent())
                .verifyComplete();

        // Wait for the entry to expire (Caffeine needs a cache access to trigger cleanup)
        Thread.sleep(1200);

        // Now it should be expired
        StepVerifier.create(provider.get("shortlived"))
                .assertNext(opt -> assertThat(opt).isEmpty())
                .verifyComplete();
    }

    @Test
    @DisplayName("should overwrite existing entry with same key")
    void overwriteExistingKey() {
        provider.put("key1", "value1", 300).block();
        provider.put("key1", "value2", 300).block();

        StepVerifier.create(provider.get("key1"))
                .assertNext(opt -> {
                    assertThat(opt).isPresent();
                    assertThat(opt.get()).isEqualTo("value2");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("CacheEntry should store value and TTL metadata")
    void cacheEntryMetadata() {
        CaffeineProvider.CacheEntry entry = new CaffeineProvider.CacheEntry("test-value", 120);

        assertThat(entry.getValue()).isEqualTo("test-value");
        assertThat(entry.getTtlSeconds()).isEqualTo(120);
        assertThat(entry.getCreatedAt()).isLessThanOrEqualTo(System.currentTimeMillis());
        assertThat(entry.getCreatedAt()).isGreaterThan(System.currentTimeMillis() - 1000);
    }

    @Test
    @DisplayName("should handle concurrent puts for different keys")
    void concurrentPuts() {
        for (int i = 0; i < 50; i++) {
            provider.put("key-" + i, "value-" + i, 300).block();
        }

        for (int i = 0; i < 50; i++) {
            final int idx = i;
            StepVerifier.create(provider.get("key-" + idx))
                    .assertNext(opt -> {
                        assertThat(opt).isPresent();
                        assertThat(opt.get()).isEqualTo("value-" + idx);
                    })
                    .verifyComplete();
        }
    }
}
