# Spring Gateway Toolkit — Architecture Overview

## What this is

Spring Gateway Toolkit is a thin Spring Cloud Gateway extension that adds four cross-cutting concerns to a vanilla Gateway deployment:

1. **Request/response logging** with structured JSON output and configurable body capture.
2. **Response caching** keyed on method + path + sorted query params, backed by Caffeine.
3. **Conman** — a YAML-driven mock-API framework that turns the same Gateway instance into a stub server for development and integration tests.
4. **OAuth2 security** — resource-server (JWT) and login flows, with hardcoded protection of toolkit admin endpoints.

Everything is configurable via YAML. The toolkit ships as a Spring Boot starter (`gateway-starter`) that auto-configures the four features above with sensible defaults.

## Runtime model: reactive, not servlet

Spring Cloud Gateway is built on Netty + Spring WebFlux + Project Reactor. Every request flows through `Mono`/`Flux` operators on Netty event-loop threads; there is no servlet container, no `HttpServletRequest`, no thread-per-request pool.

Two consequences for library users:

- **All toolkit filters and handlers are reactive.** When you extend `LoggingAndCachingWebFilter`, `CacheProvider`, or the Conman handler, you must return `Mono`/`Flux`. Blocking calls (`Thread.sleep`, classic JDBC, `Files.readAllBytes` on large files) will stall an event-loop thread and degrade the whole gateway.
- **Virtual threads (JEP 444) add no value on the Gateway hot path.** Virtual threads exist to make blocking code cheap; the reactive pipeline never blocks. Setting `spring.threads.virtual.enabled=true` is at best a no-op for Gateway requests and can introduce pinning issues with `synchronized` blocks inside reactive operators. If you ever bolt on a blocking integration (e.g. a JDBC ledger inside a custom filter), wrap it with `Schedulers.boundedElastic()` and consider substituting a virtual-thread executor at that scheduler boundary — never globally.

## Request flow

```
HTTP Request
    │
    ▼
Netty inbound  ──►  Spring Cloud Gateway route predicate match
                                    │
                                    ▼
                       LoggingAndCachingWebFilter (HIGHEST_PRECEDENCE)
                                    │
                  ┌─────────────────┴──────────────────┐
                  │                                    │
        cache rule matches?                  no cache rule
                  │                                    │
                  ▼                                    ▼
        CacheProvider.get(key)                 chain.filter(exchange)
                  │                                    │
       ┌──────────┴──────────┐                         │
       │                     │                         │
       ▼                     ▼                         ▼
   cache HIT             cache MISS              upstream / Conman /
   (serve cached)        (fall through)          static route response
                              │                         │
                              └────────┬────────────────┘
                                       ▼
                         capture body (if cache rule)
                                       │
                                       ▼
                         store response in cache (2xx only)
                                       │
                                       ▼
                          log request/response, emit
                                       │
                                       ▼
                                   HTTP Response
```

`LoggingAndCachingWebFilter` is registered with `Ordered.HIGHEST_PRECEDENCE` so it sees the unmodified request and the final response. Body capture is opt-in — it only wraps the exchange in `BodyCaptureRequest` / `BodyCaptureResponse` when *either* a logging rule with `exclude-body: false` matches *or* a cache rule matches. This keeps the heap cost off the hot path for routes that don't need it.

## Module layout

```
gateway-app  (runnable Spring Boot application — Docker image source)
    └── gateway-starter
        └── gateway-core

gateway-starter  (Spring Boot auto-configuration only — no business logic)
    └── gateway-core

gateway-core  (filters, cache providers, Conman, configuration properties)
    ├── spring-cloud-starter-gateway
    ├── spring-boot-starter-webflux
    ├── spring-boot-starter-cache
    ├── caffeine
    ├── networknt:json-schema-validator
    └── lazydevs:persistence-utils + app-building-commons
```

See [module-structure.md](module-structure.md) for the per-package breakdown and extension points.

## How features are activated

The starter's `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` lists `GatewayToolkitAutoConfiguration` and `SecurityAutoConfiguration` unconditionally. **Putting `gateway-starter` on the classpath is what loads the auto-configuration** — there is no enabling annotation. Each individual feature is then gated by a `@ConditionalOnProperty` check on `gateway.<feature>.enabled` (see the default matrix below). Set the property via `application.yml`, an environment variable, or a `--gateway.x.enabled=true` command-line flag.

> `1.0.x` shipped an `@EnableGatewayToolkit` annotation that pre-seeded those properties as compile-time defaults. It was removed in `1.1.0` because it didn't actually gate anything and consistently confused readers into thinking it did. Configure features in YAML.

## Default `enabled` matrix

| Feature   | Default in `application.yml` | `matchIfMissing` on conditional? | Effect when no config provided |
|-----------|------------------------------|----------------------------------|--------------------------------|
| logging   | `true`                       | n/a (property explicit)          | Filter loads, no body capture unless a rule matches |
| CORS      | `true`                       | yes                              | Permissive defaults; pin `allowed-origins` in production |
| caching   | `false`                      | no                               | No `CacheProvider` bean; filter loads but skips cache lookup |
| conman    | `false`                      | no                               | No mock handler, no admin endpoints |
| security  | `false`                      | no                               | `/gateway/cache/**` and `/conman/admin/**` are **public** unless you flip this to `true` |

Production deployments should flip `gateway.security.enabled=true` whenever caching or Conman are enabled, because their admin endpoints are only protected by `SecurityAutoConfiguration`. See [security.md](security.md).

## Design decisions

### Reactive end-to-end
Gateway is I/O-bound. Reactor + Netty handles many concurrent connections per OS thread, and the Spring Cloud Gateway team has chosen WebFlux as the only supported runtime. The toolkit follows that decision; there is no servlet-stack version, and there are no plans to add one.

### Cache provider as an SPI, but only Caffeine ships in-tree
`CacheProvider` is a `Mono`-returning interface with `get`/`put`/`invalidate`/`clear`/`getInternalKeys`. The starter ships `CaffeineProvider` (default when `gateway.caching.enabled=true`) and `NoOpCacheProvider` (fallback). To plug in a different backend (Redis, Memcached, Hazelcast), register a `@Bean` of type `CacheProvider`; the `@ConditionalOnMissingBean(CacheProvider.class)` guard on the auto-configured beans will step aside. See [caching.md](caching.md) for the worked Redis example.

### Caffeine, not Spring Cache abstraction
`CaffeineProvider` uses Caffeine's `Expiry` interface directly so each cache entry can have its own TTL (matching the YAML rules). Spring's `CacheManager` abstraction works on a single TTL per cache and would require pre-defining a cache per TTL bucket — too rigid for per-route TTLs. Trade-off: you give up Spring Cache's `@Cacheable` annotation; gain per-entry TTL.

### YAML over annotations
All toolkit features are configured in `application.yml`, not via custom annotations. Annotation-driven route configuration in a Gateway context would fight Spring Cloud Gateway's own annotation-free route DSL. Keeping config in YAML also matches how operators deploy and override settings (environment variables, ConfigMaps, profile files).

### Hardcoded admin path protection
`SecurityAutoConfiguration` protects `/gateway/cache/**` and `/conman/admin/**` with `.authenticated()` before applying user-defined `guest-allowed-paths`. This is deliberately *not* user-configurable — an operator should not be able to permit-all the cache invalidation API by accident. If you genuinely need to expose these endpoints, run the gateway behind a separate auth layer (mTLS, mesh sidecar) and disable toolkit security.

## Where to look next

- [module-structure.md](module-structure.md) — package layout, key classes, and extension points.
- [configuration-reference.md](configuration-reference.md) — every YAML property with its default.
- [caching.md](caching.md), [logging.md](logging.md), [conman.md](conman.md), [security.md](security.md) — feature-specific deep-dives.
- [deployment.md](deployment.md) — building from source, Docker, profiles, SonarCloud wiring.
