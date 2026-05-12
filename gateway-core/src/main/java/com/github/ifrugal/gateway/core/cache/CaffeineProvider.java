package com.github.ifrugal.gateway.core.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.ifrugal.gateway.core.config.CachingProperties;
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
 *
 * <p>Uses Caffeine's {@code expireAfter} (variable expiration) to enforce per-entry TTL.
 * Each entry's TTL is specified at insertion time and stored in the {@link CacheEntry}.</p>
 */
@Slf4j
public class CaffeineProvider implements CacheProvider {

    private final Cache<String, CacheEntry> cache;

    public CaffeineProvider(CachingProperties properties) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(properties.getMaxSize())
                .expireAfter(new Expiry<String, CacheEntry>() {
                    @Override
                    public long expireAfterCreate(String key, CacheEntry entry, long currentTime) {
                        return TimeUnit.SECONDS.toNanos(entry.getTtlSeconds());
                    }

                    @Override
                    public long expireAfterUpdate(String key, CacheEntry entry, long currentTime, long currentDuration) {
                        return TimeUnit.SECONDS.toNanos(entry.getTtlSeconds());
                    }

                    @Override
                    public long expireAfterRead(String key, CacheEntry entry, long currentTime, long currentDuration) {
                        return currentDuration; // Don't reset TTL on read
                    }
                })
                .recordStats()
                .build();
        log.info("Caffeine cache initialized with maxSize={}, defaultTtl={}s",
                properties.getMaxSize(), properties.getDefaultTtl());
    }

    @Override
    public Mono<Optional<String>> get(String key) {
        return Mono.fromSupplier(() -> {
            CacheEntry entry = cache.getIfPresent(key);
            if (entry != null) {
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
                        e -> Map.of(
                                "ttlSeconds", e.getValue().getTtlSeconds(),
                                "createdAt", new Date(e.getValue().getCreatedAt())
                        )
                ));
    }

    /**
     * Cache entry that stores the value along with TTL metadata.
     * Caffeine handles actual expiration via {@code expireAfter}; this class
     * only stores metadata for introspection.
     */
    static class CacheEntry {
        private final String value;
        private final long ttlSeconds;
        private final long createdAt;

        public CacheEntry(String value, long ttlSeconds) {
            this.value = value;
            this.ttlSeconds = ttlSeconds;
            this.createdAt = System.currentTimeMillis();
        }

        public String getValue() {
            return value;
        }

        public long getTtlSeconds() {
            return ttlSeconds;
        }

        public long getCreatedAt() {
            return createdAt;
        }
    }
}
