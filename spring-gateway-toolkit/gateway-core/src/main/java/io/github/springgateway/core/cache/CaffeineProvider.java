package io.github.springgateway.core.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.springgateway.core.config.CachingProperties;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Caffeine-based implementation of the CacheProvider interface.
 * Provides high-performance in-memory caching with per-entry TTL support.
 */
@Slf4j
public class CaffeineProvider implements CacheProvider {

    private final Cache<String, CacheEntry> cache;

    public CaffeineProvider(CachingProperties properties) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(properties.getMaxSize())
                .expireAfterWrite(properties.getDefaultTtl(), TimeUnit.SECONDS)
                .recordStats()
                .build();
        log.info("Caffeine cache initialized with maxSize={}, defaultTtl={}s",
                properties.getMaxSize(), properties.getDefaultTtl());
    }

    @Override
    public Mono<Optional<String>> get(String key) {
        return Mono.fromSupplier(() -> {
            CacheEntry entry = cache.getIfPresent(key);
            if (entry != null && !entry.isExpired()) {
                log.debug("Cache hit for key: {}", key);
                return Optional.of(entry.getValue());
            }
            log.debug("Cache miss for key: {}", key);
            return Optional.empty();
        });
    }

    @Override
    public Mono<Void> put(String key, String value, long ttlSeconds) {
        return Mono.fromRunnable(() -> {
            CacheEntry entry = new CacheEntry(value, ttlSeconds);
            cache.put(key, entry);
            log.debug("Cached value for key: {} with TTL: {} seconds", key, ttlSeconds);
        });
    }

    @Override
    public Mono<Void> invalidate(String key) {
        return Mono.fromRunnable(() -> {
            cache.invalidate(key);
            log.debug("Invalidated cache for key: {}", key);
        });
    }

    @Override
    public Mono<Void> clear() {
        return Mono.fromRunnable(() -> {
            cache.invalidateAll();
            log.info("Cache cleared");
        });
    }

    @Override
    public Map<String, Object> getInternalKeys() {
        return cache.asMap().entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> new Date(e.getValue().expirationTime)
                ));
    }

    /**
     * Private cache entry class to manage TTL at the individual entry level.
     */
    private static class CacheEntry {
        private final String value;
        private final long expirationTime;

        public CacheEntry(String value, long ttlSeconds) {
            this.value = value;
            this.expirationTime = System.currentTimeMillis() + (ttlSeconds * 1000);
        }

        public String getValue() {
            return value;
        }

        public boolean isExpired() {
            return System.currentTimeMillis() > expirationTime;
        }
    }
}
