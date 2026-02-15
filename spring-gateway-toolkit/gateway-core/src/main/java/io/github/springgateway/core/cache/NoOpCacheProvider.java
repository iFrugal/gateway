package io.github.springgateway.core.cache;

import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/**
 * No-operation cache provider implementation.
 * Used when caching is disabled or no cache provider is configured.
 */
public class NoOpCacheProvider implements CacheProvider {

    @Override
    public Mono<Optional<String>> get(String key) {
        return Mono.just(Optional.empty());
    }

    @Override
    public Mono<Void> put(String key, String value, long ttlSeconds) {
        return Mono.empty();
    }

    @Override
    public Mono<Void> invalidate(String key) {
        return Mono.empty();
    }

    @Override
    public Map<String, Object> getInternalKeys() {
        return Collections.emptyMap();
    }

    @Override
    public Mono<Void> clear() {
        return Mono.empty();
    }
}
