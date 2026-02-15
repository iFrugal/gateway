package io.github.springgateway.core.cache;

import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Optional;

/**
 * Interface for the cache provider used by the gateway.
 * Implementations should be reactive and thread-safe.
 */
public interface CacheProvider {

    /**
     * Get a cached value by key.
     *
     * @param key the cache key
     * @return Mono containing Optional with the cached value, or empty Optional if not found
     */
    Mono<Optional<String>> get(String key);

    /**
     * Put a value into the cache with a specified TTL.
     *
     * @param key the cache key
     * @param value the value to cache
     * @param ttlSeconds time-to-live in seconds
     * @return Mono that completes when the value is cached
     */
    Mono<Void> put(String key, String value, long ttlSeconds);

    /**
     * Invalidate a cache entry.
     *
     * @param key the cache key to invalidate
     * @return Mono that completes when the key is invalidated
     */
    Mono<Void> invalidate(String key);

    /**
     * Get all cached keys with their expiration times.
     *
     * @return Map of cache keys to their expiration metadata
     */
    Map<String, Object> getInternalKeys();

    /**
     * Clear all entries from the cache.
     *
     * @return Mono that completes when the cache is cleared
     */
    default Mono<Void> clear() {
        return Mono.empty();
    }
}
