# Spring Gateway Toolkit - Architecture Overview

## Introduction

Spring Gateway Toolkit is a production-ready Spring Cloud Gateway extension providing advanced caching, logging, security, and mock capabilities. Built on reactive principles with Spring WebFlux, it delivers zero-configuration defaults while supporting annotation-driven customization.

## High-Level Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    HTTP Request                              │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ▼
        ┌────────────────────────────┐
        │  Spring Cloud Gateway      │
        │  (Filter Chain)            │
        └────────────┬───────────────┘
                     │
                     ▼
        ┌────────────────────────────┐
        │ LoggingAndCachingWebFilter │
        │ (gateway-core)             │
        └────────────┬───────────────┘
                     │
          ┌──────────┴──────────┐
          │                     │
          ▼                     ▼
    ┌─────────────┐      ┌─────────────┐
    │ Cache Hit?  │      │ Upstream    │
    │ (Redis/Mem) │      │ Service     │
    └──────┬──────┘      └──────┬──────┘
           │                    │
           ▼                    ▼
    ┌─────────────────────────────┐
    │  Response Processing        │
    │  (Logging, Transformation)  │
    └────────────┬────────────────┘
                 │
                 ▼
        ┌────────────────────────┐
        │  HTTP Response         │
        └────────────────────────┘
```

## Request Flow

### 1. Gateway Filter Chain Entry
Requests enter through Spring Cloud Gateway's filter chain. The toolkit registers `LoggingAndCachingWebFilter` as a high-priority filter intercepting all routes.

### 2. Cache Lookup
The `LoggingAndCachingWebFilter` checks configured cache stores in order:
- **Redis Cache** (distributed, multi-instance)
- **Memory Cache** (local, single-instance)
- **Conman Mock** (testing/development)

Cache key generation is based on request method, path, and configurable headers.

### 3. Upstream Routing Decision
- **Cache Hit**: Returns cached response, updates TTL
- **Cache Miss**: Routes to upstream service, captures response
- **Cache Storage**: Response stored with configured TTL before returning to client

### 4. Logging & Observability
All stages emit structured logs:
- Request metadata (method, path, headers, client IP)
- Cache decision (hit/miss/error)
- Response metrics (status code, latency, size)
- Upstream latency and errors

## Module Dependency Graph

```
gateway-app (Runnable Application)
    └── gateway-starter
        └── gateway-core

gateway-starter (Spring Boot Auto-Configuration)
    └── gateway-core

gateway-core (Core Library)
    ├── spring-cloud-gateway
    ├── spring-webflux
    ├── spring-data-redis (optional)
    └── testing/mock framework
```

### Module Responsibilities

**gateway-core**
- WebFilter implementations
- Cache abstraction and providers
- Security configuration classes
- Annotation-driven configuration
- Conman mock framework
- Logging and observability utilities

**gateway-starter**
- `@AutoConfiguration` for zero-config setup
- Property binding via `@ConfigurationProperties`
- Conditional bean registration
- Health indicators
- Metrics instrumentation

**gateway-app**
- Standalone runnable application
- Example configurations
- Pre-configured profiles (dev, staging, production)
- Docker image support

## Design Principles

### Reactive-First with WebFlux
All components are built on reactive streams using Spring WebFlux. Non-blocking I/O throughout the request pipeline ensures high throughput and minimal thread consumption.

### Annotation-Driven Configuration
Configuration happens via annotations and properties, not XML:
```java
@CacheableRoute(cacheName = "api-cache", ttl = 300)
@EnableSecurityConfig(roles = {"ADMIN"})
```

### Zero-Configuration Defaults
Deploy with sensible defaults requiring zero setup:
- In-memory caching enabled by default
- Structured JSON logging configured
- Basic security rules applied
- All features optional via explicit activation

### Composable and Extensible
- Plugin architecture for custom cache providers
- Annotation processors for custom security rules
- WebFilter composition for layered functionality
- Event-driven architecture for extensibility

## Key Design Decisions

### 1. Reactive Streams Over Threads
**Decision**: Use WebFlux and reactive patterns exclusively.
**Rationale**: Gateway is I/O-bound; threads become bottleneck at scale. Reactive model handles 10x more concurrent connections with same resources.
**Trade-off**: Steeper learning curve; blocking operations forbidden.

### 2. Separate Cache Abstraction Layer
**Decision**: Abstract cache provider behind interface.
**Rationale**: Supports Redis, Memcached, in-memory, or custom implementations without core changes.
**Trade-off**: Additional abstraction layer adds minimal overhead but slight complexity.

### 3. Annotation-Driven Over Declarative Files
**Decision**: Use annotations for route-specific config rather than route definition files.
**Rationale**: Configuration lives with code; easier to maintain; type-safe.
**Trade-off**: Requires Spring Boot; less flexible for dynamic route changes.

### 4. Structured Logging via SLF4J
**Decision**: SLF4J with JSON output format.
**Rationale**: Machine-parseable logs for aggregation systems; reduces parsing overhead.
**Trade-off**: Slightly larger log size; requires JSON-aware log viewers.

### 5. Mock Framework for Testing
**Decision**: Embed Conman mock framework for development/testing.
**Rationale**: Eliminates need for external mock servers; enables realistic gateway testing.
**Trade-off**: Adds dependency; requires understanding of mock configuration.

## Performance Considerations

- **Cache-Hit Latency**: < 5ms (in-memory) / < 10ms (Redis)
- **Cache-Miss Latency**: Upstream latency + 1-2ms overhead
- **Memory Footprint**: ~50MB base + cache size
- **Thread Pool**: Async throughout; minimal thread usage

## Security Architecture

- JWT/OAuth2 authentication via annotations
- Rate limiting per route
- CORS configuration
- Request/response sanitization
- Upstream service discovery validation

## Future Extensibility

The architecture supports:
- Circuit breaker integration (Resilience4j)
- Distributed tracing (Micrometer, Sleuth)
- Custom filter plugins
- Multiple upstream load-balancing strategies
- Event streaming for audit logs
