package io.github.springgateway.core.controller;

import io.github.springgateway.core.cache.CacheProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * REST controller for managing the response cache.
 */
@RestController
@RequestMapping("/gateway/cache")
@RequiredArgsConstructor
@Slf4j
@ConditionalOnBean(CacheProvider.class)
public class CacheController {

    private final CacheProvider cacheProvider;

    /**
     * Get a cached value by key.
     *
     * @param key Cache key
     * @return Cached value or error if not found
     */
    @GetMapping("/{key}")
    public Mono<String> getFromCache(@PathVariable("key") String key) {
        return cacheProvider.get(key)
                .flatMap(optional -> optional
                        .map(Mono::just)
                        .orElseGet(() -> Mono.error(new IllegalArgumentException("Key not found or expired: " + key))));
    }

    /**
     * Put a value into the cache.
     *
     * @param key Cache key
     * @param value Value to cache
     * @param ttlSeconds TTL in seconds (default: 300)
     * @return Confirmation message
     */
    @PostMapping("/{key}")
    public Mono<Map<String, Object>> putInCache(
            @PathVariable("key") String key,
            @RequestParam("value") String value,
            @RequestParam(value = "ttlSeconds", defaultValue = "300") long ttlSeconds) {
        return cacheProvider.put(key, value, ttlSeconds)
                .thenReturn(Map.of(
                        "status", "success",
                        "key", key,
                        "ttlSeconds", ttlSeconds
                ));
    }

    /**
     * Invalidate a cache entry.
     *
     * @param key Cache key to invalidate
     * @return Confirmation message
     */
    @DeleteMapping("/{key}")
    public Mono<Map<String, String>> invalidateKey(@PathVariable("key") String key) {
        return cacheProvider.invalidate(key)
                .thenReturn(Map.of(
                        "status", "success",
                        "message", "Invalidated cache for key: " + key
                ));
    }

    /**
     * List all cache keys with their expiration times.
     *
     * @return Map of keys to expiration metadata
     */
    @GetMapping
    public Map<String, Object> listKeys() {
        return cacheProvider.getInternalKeys();
    }

    /**
     * Clear all cache entries.
     *
     * @return Confirmation message
     */
    @DeleteMapping
    public Mono<Map<String, String>> clearAll() {
        return cacheProvider.clear()
                .thenReturn(Map.of(
                        "status", "success",
                        "message", "Cache cleared"
                ));
    }
}
