package com.github.ifrugal.gateway.core.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("NoOpCacheProvider")
class NoOpCacheProviderTest {

    private NoOpCacheProvider provider;

    @BeforeEach
    void setUp() {
        provider = new NoOpCacheProvider();
    }

    @Test
    @DisplayName("get should always return empty Optional")
    void getAlwaysEmpty() {
        StepVerifier.create(provider.get("any-key"))
                .assertNext(opt -> assertThat(opt).isEmpty())
                .verifyComplete();
    }

    @Test
    @DisplayName("put should complete without error")
    void putCompletes() {
        StepVerifier.create(provider.put("key", "value", 300))
                .verifyComplete();
    }

    @Test
    @DisplayName("invalidate should complete without error")
    void invalidateCompletes() {
        StepVerifier.create(provider.invalidate("key"))
                .verifyComplete();
    }

    @Test
    @DisplayName("clear should complete without error")
    void clearCompletes() {
        StepVerifier.create(provider.clear())
                .verifyComplete();
    }

    @Test
    @DisplayName("getInternalKeys should return empty map")
    void getInternalKeysEmpty() {
        assertThat(provider.getInternalKeys()).isEmpty();
    }

    @Test
    @DisplayName("put then get should still return empty (no-op)")
    void putThenGetStillEmpty() {
        provider.put("key", "value", 300).block();

        StepVerifier.create(provider.get("key"))
                .assertNext(opt -> assertThat(opt).isEmpty())
                .verifyComplete();
    }
}
