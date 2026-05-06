# Spring Gateway Toolkit - Module Structure Deep-Dive

This document provides a comprehensive overview of the Spring Gateway Toolkit's architecture, module organization, and extension points.

## Project Layout

The toolkit is organized as a Maven multi-module project with three core modules:

```
spring-gateway-toolkit/
├── gateway-core/           # Core library with all business logic
├── gateway-starter/        # Spring Boot auto-configuration starter
├── gateway-app/            # Standalone runnable application
└── docker/                 # Docker deployment artifacts
```

## Gateway-Core Module

The **gateway-core** module contains the core functionality and is the heart of the toolkit.

### Package Organization

#### 1. `com.github.ifrugal.gateway.core.annotation`
Provides annotation-based configuration.

**Key Classes:**
- `EnableGatewayToolkit` - Main annotation for enabling toolkit features with attribute toggles
  - `enableLogging()` - Toggle request/response logging
  - `enableCaching()` - Toggle response caching
  - `enableConman()` - Toggle mock API framework
- `GatewayToolkitImportSelector` - ImportSelector implementation for conditional bean registration

**Usage Example:**
```java
@EnableGatewayToolkit(enableLogging = true, enableCaching = true)
```

#### 2. `com.github.ifrugal.gateway.core.cache`
Cache provider abstraction and implementations.

**Key Classes:**
- `CacheProvider` (interface) - Abstraction for cache implementations
  - `get(key)` - Retrieve cached values reactively
  - `put(key, value, ttlSeconds)` - Store with TTL
  - `invalidate(key)` - Remove specific entries
  - `getInternalKeys()` - Retrieve cache metadata
  - `clear()` - Clear all entries
- `CaffeineProvider` - Production cache using Caffeine library
  - Supports TTL-based expiration
  - Configurable max size
  - Thread-safe and reactive
- `NoOpCacheProvider` - No-operation cache for disabled caching

**Extension Point:** Implement `CacheProvider` to use custom cache backends (Redis, Memcached, etc.).

#### 3. `com.github.ifrugal.gateway.core.config`
Configuration properties for all toolkit features.

**Key Classes:**
- `LoggingProperties` - Request/response logging configuration
  - `enabled` - Enable/disable logging
  - `level` - Log level (DEBUG, INFO, WARN)
  - `ignorePaths` - Paths to exclude from logging
- `CachingProperties` - Cache configuration
  - `enabled` - Enable/disable caching
  - `maxSize` - Maximum cache entries (default: 1000)
  - `defaultTtl` - Default TTL in seconds (default: 300)
- `CorsProperties` - CORS configuration
  - `enabled` - Enable/disable CORS filter
  - `allowedOrigins` - List of allowed origins
  - `allowedMethods` - Allowed HTTP methods
  - `allowedHeaders` - Allowed request headers
  - `exposedHeaders` - Response headers exposed to clients
  - `maxAge` - Preflight cache duration
- `SecurityProperties` - OAuth2 and authentication configuration
  - `enabled` - Enable/disable security
  - `oauth2` - OAuth2 provider and client configuration
  - `guestAllowedPaths` - Paths that bypass authentication

#### 4. `com.github.ifrugal.gateway.core.conman`
Mock API framework (Conman) for testing and development.

**Key Classes:**
- `ConmanProperties` - Mock API configuration
  - `enabled` - Enable/disable mock API
  - `servletUriMappings` - URI patterns for mock endpoints
  - Mock data directory configuration
- `ConmanCache` - In-memory cache for mock configurations
  - Loads mock definitions from YAML
  - Matches requests to mock responses
  - Supports response templating
- `ConmanServlet` - HTTP servlet handling mock requests
  - Processes incoming mock requests
  - Returns configured responses
- `ConmanAdminController` - REST API for managing mocks at runtime
  - List active mocks
  - Add/update mock configurations
  - Delete mocks
  - Reload from files

#### 5. `com.github.ifrugal.gateway.core.conman.validation`
Request validation for mock APIs.

**Key Classes:**
- `RequestValidator` - Validates incoming requests against JSON Schema
  - Schema-based validation
  - Detailed error reporting
  - Integration with mock framework

#### 6. `com.github.ifrugal.gateway.core.controller`
REST controllers for toolkit management.

**Key Classes:**
- `CacheController` - Endpoints for cache management
  - GET `/gateway/cache` - List cache statistics
  - GET `/gateway/cache/keys` - List all cache keys
  - DELETE `/gateway/cache/{key}` - Invalidate specific cache entry
  - DELETE `/gateway/cache` - Clear entire cache

#### 7. `com.github.ifrugal.gateway.core.filter`
WebFilter implementations for request/response processing.

**Key Classes:**
- `LoggingAndCachingWebFilter` - Main filter for logging and caching
  - Captures and logs request/response details
  - Implements response caching logic
  - Configurable path exclusions
  - Performance-optimized with minimal overhead
- `BodyCaptureRequest` - Request wrapper for reading body multiple times
- `BodyCaptureResponse` - Response wrapper for body interception
- `BodyCaptureExchange` - Serverless exchange wrapper

#### 8. `com.github.ifrugal.gateway.core.filter.utils`
Utility classes for filter operations.

**Key Classes:**
- `RequestMatcher` - Path pattern matching for filters
  - Ant-style pattern matching
  - Efficient prefix/suffix checks

## Gateway-Starter Module

The **gateway-starter** module provides Spring Boot auto-configuration for easy integration.

### Key Classes

#### `GatewayToolkitAutoConfiguration`
Main auto-configuration class (@AutoConfiguration).

**Responsibilities:**
- Registers configuration properties beans
- Conditionally creates cache provider beans
- Configures logging and caching web filter
- Sets up CORS filter
- Handles Conman mock API configuration

**Conditional Bean Registration:**
```java
@ConditionalOnProperty(prefix = "gateway.caching", name = "enabled", havingValue = "true")
public CacheProvider caffeineProvider(CachingProperties cachingProperties)

@ConditionalOnProperty(prefix = "gateway.cors", name = "enabled", havingValue = "true")
public WebFilter gatewayCorsFilter(CorsProperties corsProperties)

@ConditionalOnProperty(prefix = "gateway.conman", name = "enabled", havingValue = "true")
public static class ConmanAutoConfiguration { ... }
```

#### `SecurityAutoConfiguration`
Security auto-configuration for OAuth2 and authentication.

**Responsibilities:**
- Configures Spring Security FilterChain when enabled
- Sets up OAuth2 resource server with JWT
- Protects admin endpoints (/gateway/cache/**, /conman/admin/**)
- Configures OpenAPI/Swagger security schemes
- Provides authentication entry points

**Key Features:**
- Admin endpoints always require authentication
- Guest paths configurable via `gateway.security.guest-allowed-paths`
- OAuth2 flow integration with Swagger UI
- Automatic redirect to OAuth2 login for protected resources

### META-INF Configuration

**File:** `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

```
com.github.ifrugal.gateway.autoconfigure.GatewayToolkitAutoConfiguration
com.github.ifrugal.gateway.autoconfigure.SecurityAutoConfiguration
```

This file registers auto-configurations for Spring Boot to discover and apply automatically.

## Gateway-App Module

The **gateway-app** module is a standalone, fully-configured Spring Boot Gateway application.

### Key Class

#### `GatewayApplication`
Main Spring Boot application class.

**Features:**
- Ready-to-run gateway application
- OpenAPI/Swagger UI aggregation
- Custom OpenAPI operation customizer for common headers
- Multiple profile support (default, eureka, consul, docker, static)
- Built-in Conman mock API framework
- Request/response logging
- Response caching
- OAuth2 security integration

**Configuration Profiles:**
- `application.yml` - Default configuration
- `application-eureka.yml` - Eureka service discovery
- `application-consul.yml` - Consul service discovery
- `application-static.yml` - Static route configuration

## Dependency Flow

```
gateway-app
    ↓
    └── depends on → gateway-starter
                        ↓
                        └── depends on → gateway-core
                                           ↓
                                           └── Spring Cloud Gateway
                                           └── Spring Boot WebFlux
```

**Dependency Layers:**

1. **gateway-core** (Library)
   - No Spring Boot application dependency
   - Core abstractions and implementations
   - Can be used independently in other projects

2. **gateway-starter** (Spring Boot Starter)
   - Depends on gateway-core
   - Provides auto-configuration
   - Automatically discovered by Spring Boot
   - Can be added as a library dependency

3. **gateway-app** (Application)
   - Depends on gateway-starter
   - Fully functional, standalone application
   - Can be run directly or containerized

## Extension Points

### 1. Custom Cache Provider

Implement `CacheProvider` interface for custom cache backends:

```java
public class RedisStreamCacheProvider implements CacheProvider {
    @Override
    public Mono<Optional<String>> get(String key) {
        // Redis implementation
    }

    @Override
    public Mono<Void> put(String key, String value, long ttlSeconds) {
        // Redis implementation
    }

    // ... implement other methods
}
```

Register in Spring configuration:
```java
@Configuration
public class CacheConfiguration {
    @Bean
    public CacheProvider cacheProvider() {
        return new RedisStreamCacheProvider();
    }
}
```

### 2. Custom WebFilter

Create custom filters for additional processing:

```java
public class CustomSecurityWebFilter implements WebFilter {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // Custom logic
        return chain.filter(exchange);
    }
}
```

### 3. Custom Properties

Extend configuration by creating new `@ConfigurationProperties` classes:

```java
@ConfigurationProperties(prefix = "gateway.custom")
public class CustomProperties {
    private boolean enabled = true;
    private String apiKey;
    // getters/setters
}
```

### 4. Custom Controllers

Add REST endpoints to the `gateway-core` module:

```java
@RestController
@RequestMapping("/gateway/custom")
public class CustomController {
    @GetMapping("/status")
    public Mono<Map<String, String>> status() {
        // Custom endpoint logic
    }
}
```

### 5. Conman Mock Extensions

Extend mock API capabilities:
- Custom response transformers
- Request validators
- Template processors

## Build and Testing

**Build:** All modules built with `mvn clean install`

**Testing:** Unit tests in each module under `src/test/java`

**Code Coverage:** JaCoCo reports generated in `target/site/jacoco/`

**Documentation:** Javadoc generated for all public APIs

## Best Practices

1. **Security:** Always enable `gateway.security.enabled: true` in production
2. **Caching:** Tune `maxSize` and `defaultTtl` based on traffic patterns
3. **Logging:** Disable DEBUG logging in production; use ignore-paths for high-volume endpoints
4. **Extension:** Implement `CacheProvider` for production cache backends like Redis
5. **Docker:** Use the provided multi-stage Dockerfile for optimized images
6. **Monitoring:** Enable Actuator endpoints for health checks and metrics
