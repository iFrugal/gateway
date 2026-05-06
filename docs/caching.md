# Caching Subsystem

The Spring Gateway Toolkit provides a reactive, high-performance response caching system designed for API gateway scenarios. This document covers configuration, usage, cache key generation, and extension points.

## Overview

The caching subsystem intercepts HTTP requests and responses, enabling short-circuit serving of cached responses without hitting upstream services. Cache decisions are driven by matching request paths and HTTP methods against configured rules, each with its own TTL (time-to-live).

**Key features:**
- Reactive, non-blocking cache operations using Project Reactor
- Per-entry TTL with variable expiration via Caffeine
- Deterministic cache key generation with sorted query parameters
- REST API for manual cache management
- Rule-based caching for different paths and methods
- Optional no-op provider for disabling caching without code changes

## Configuration

Cache configuration is managed via `application.yaml` or `application.properties` under the `gateway.caching.*` prefix:

```yaml
gateway:
  caching:
    enabled: true                    # Enable/disable caching globally
    provider: caffeine               # Cache provider (only 'caffeine' currently)
    default-ttl: 86400              # Default TTL in seconds (1 day)
    max-size: 10000                 # Maximum cache entries
    rules:
      - paths: ["/api/products/**", "/api/categories/**"]
        methods: [GET]
        ttl: 3600                   # Override TTL for this rule (1 hour)
      - paths: ["/api/users/**"]
        methods: ["*"]              # Cache all HTTP methods
        ttl: 1800                   # 30 minutes
```

### Configuration Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `gateway.caching.enabled` | boolean | `false` | Enable caching |
| `gateway.caching.provider` | string | `"caffeine"` | Provider implementation |
| `gateway.caching.default-ttl` | long | `86400` | Default TTL in seconds |
| `gateway.caching.max-size` | int | `10000` | Maximum cache entries before eviction |
| `gateway.caching.rules[].paths` | List<String> | - | Ant-style path patterns to match |
| `gateway.caching.rules[].methods` | List<String> | - | HTTP methods (`GET`, `POST`, etc. or `"*"` for all) |
| `gateway.caching.rules[].ttl` | long | - | Rule-specific TTL override (seconds) |

## Cache Key Generation

Cache keys are generated deterministically from the incoming request to ensure consistent retrieval regardless of parameter order:

**Format:** `METHOD:path?sorted-query-params`

**Example:**
- Request: `GET /api/products?color=red&sort=name`
- Key: `GET:/api/products?color=red&sort=name`

**Algorithm:**
1. Concatenate HTTP method and URI path
2. Split query parameters by `&`
3. Sort parameters alphabetically
4. Rejoin with `&`

This ensures that requests with identical parameters in different orders generate the same cache key, maximizing cache hit rates.

## Cache Providers

### CacheProvider Interface

All providers implement the `CacheProvider` interface with reactive methods:

```java
public interface CacheProvider {
    Mono<Optional<String>> get(String key);
    Mono<Void> put(String key, String value, long ttlSeconds);
    Mono<Void> invalidate(String key);
    Mono<Void> clear();
    Map<String, Object> getInternalKeys();
}
```

### CaffeineProvider (Production)

Uses [Caffeine](https://github.com/ben-manes/caffeine), a high-performance in-memory cache with:

- **Per-entry TTL:** Each cached entry has its own TTL via Caffeine's `expireAfter` configuration
- **Automatic expiration:** Expired entries are removed automatically by Caffeine
- **Memory efficiency:** LRU eviction when `max-size` is reached
- **Statistics:** Built-in cache hit/miss statistics for monitoring

**CacheEntry Structure:**
```java
static class CacheEntry {
    private final String value;        // Response body
    private final long ttlSeconds;     // TTL for this entry
    private final long createdAt;      // Creation timestamp
}
```

TTL is not double-applied; only Caffeine's built-in expiry mechanism is used for actual expiration.

### NoOpCacheProvider (Development/Disabled)

Returns early from all cache operations without side effects. Useful for development or when caching is disabled (`enabled: false`).

## Request/Response Flow

The `LoggingAndCachingWebFilter` (highest precedence) handles caching:

1. **Ignore path check:** Skip health checks, swagger docs, etc.
2. **Rule matching:** Determine if request path/method matches any caching rules
3. **Cache lookup:** Check if a cached response exists for the generated key
4. **Cache hit:** Short-circuit upstream call, return cached response with `X-Cache: HIT` header
5. **Cache miss:** Forward request to upstream, capture response body
6. **Cache store:** On successful 2xx response, store in cache with rule's TTL

**Example response header for cache hits:**
```
X-Cache: HIT
```

## REST Management API

The `CacheController` provides endpoints to manage the cache manually:

### Get Cached Value
```
GET /gateway/cache/{key}
```
Returns the cached value or 400 error if expired/not found.

### Put Value in Cache
```
POST /gateway/cache/{key}?value=<value>&ttlSeconds=<ttl>
```
Stores a value with optional TTL (default: 300 seconds).

### List All Cache Keys
```
GET /gateway/cache
```
Returns map of all cached keys with metadata (TTL, creation timestamp).

### Invalidate a Key
```
DELETE /gateway/cache/{key}
```
Immediately removes a cached entry.

### Clear All Cache
```
DELETE /gateway/cache
```
Removes all cached entries.

## Extension Points

### Custom Cache Provider

Implement `CacheProvider` to use a different cache backend (Redis, Memcached, etc.):

```java
public class RedisProvider implements CacheProvider {
    private final RedisTemplate<String, String> template;

    @Override
    public Mono<Optional<String>> get(String key) {
        return Mono.fromCallable(() ->
            Optional.ofNullable(template.opsForValue().get(key))
        );
    }

    @Override
    public Mono<Void> put(String key, String value, long ttlSeconds) {
        return Mono.fromRunnable(() ->
            template.opsForValue().set(key, value, Duration.ofSeconds(ttlSeconds))
        );
    }

    // Implement other methods...
}
```

Register your provider as a Spring bean to override the default:
```java
@Configuration
public class CacheConfig {
    @Bean
    public CacheProvider cacheProvider() {
        return new RedisProvider(redisTemplate);
    }
}
```

### Custom Rule Matching

Extend `RequestMatcher` to implement custom logic for determining which requests should be cached based on headers, authentication, or other criteria.

## Performance Considerations

- **Cache hit rate:** Optimize rules to match frequently accessed endpoints
- **TTL tuning:** Balance staleness tolerance against cache effectiveness
- **Max size:** Monitor cache growth; adjust `max-size` based on memory constraints
- **Query parameter order:** Keys are normalized, but unique query combinations increase cache size
- **Reactive backpressure:** All cache operations are non-blocking and respect reactive backpressure

## Monitoring

Cache metrics can be monitored via:

- **Cache statistics:** Access internal keys via `/gateway/cache` to inspect cache state
- **Logs:** Filter logs for `LoggingAndCachingWebFilter` debug/info messages
- **Request headers:** Look for `X-Cache: HIT` header to identify cache hits in downstream logs

Caffeine's built-in statistics are recorded but not currently exposed via a dedicated metrics endpoint.

## Common Use Cases

### Cache GET requests for a data API
```yaml
gateway:
  caching:
    enabled: true
    rules:
      - paths: ["/api/data/**"]
        methods: [GET]
        ttl: 600
```

### Cache with different TTLs per endpoint
```yaml
gateway:
  caching:
    enabled: true
    default-ttl: 3600
    rules:
      - paths: ["/api/static/**"]
        methods: [GET]
        ttl: 86400        # Long TTL for static data
      - paths: ["/api/volatile/**"]
        methods: [GET]
        ttl: 60           # Short TTL for volatile data
```

### Disable caching without code changes
```yaml
gateway:
  caching:
    enabled: false
```

When disabled, a `NoOpCacheProvider` is used that passes through all operations without overhead.
