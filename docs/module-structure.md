# Module Structure — Deep Dive

This page maps the Maven modules, the public packages inside each module, and the extension points a library user is most likely to reach for. Use it alongside the per-feature docs ([caching](caching.md), [logging](logging.md), [conman](conman.md), [security](security.md)).

## Project layout

```
spring-gateway-toolkit/
├── gateway-core/        # Filters, cache providers, Conman, config properties
├── gateway-starter/     # @AutoConfiguration only — no business logic
├── gateway-app/         # Runnable Spring Boot demo (Docker image source)
└── docker/              # Dockerfile + docker-compose for gateway-app
```

The dependency arrow runs one way:

```
gateway-app  →  gateway-starter  →  gateway-core
```

`gateway-core` has no Spring Boot auto-configuration of its own — it is plain Spring + WebFlux + Reactor and can be consumed by a non-starter Spring application that wires the beans manually. Most users should consume `gateway-starter` instead.

## gateway-core

> The `com.github.ifrugal.gateway.core.annotation` package existed in `1.0.x` for `@EnableGatewayToolkit` and `GatewayToolkitImportSelector`. Both were removed in `1.1.0` — the starter auto-loads from `META-INF/spring/...AutoConfiguration.imports` and feature gating lives entirely in YAML.

### `com.github.ifrugal.gateway.core.cache`

| Class | Notes |
|---|---|
| `CacheProvider` | SPI interface. Returns `Mono<Optional<String>>` for `get`, `Mono<Void>` for `put`/`invalidate`/`clear`, `Map<String, Object>` for `getInternalKeys`. |
| `CaffeineProvider` | Backed by Caffeine's `expireAfter` for per-entry TTL. Auto-configured when `gateway.caching.enabled=true` and no other `CacheProvider` bean is present. |
| `NoOpCacheProvider` | Returns `Optional.empty()` from every `get` and silently swallows `put`. Auto-configured when `gateway.caching.enabled` is unset and no other `CacheProvider` bean is present, so `LoggingAndCachingWebFilter` always has something to talk to. |

**Extension point.** Register a `@Bean` of type `CacheProvider` in your application config; the Caffeine bean is `@ConditionalOnMissingBean(CacheProvider.class)` and will step aside. See [caching.md](caching.md) for a reactive Redis example.

### `com.github.ifrugal.gateway.core.config`

`@ConfigurationProperties` classes (all `@Data`, no JSR-380 validation):

| Class | Prefix | Notable fields & defaults |
|---|---|---|
| `LoggingProperties` | `gateway.logging` | `enabled=true`, `level="info"`, `ignorePaths=[]`, `requests=[]`. Nested `RequestConfig` holds `paths`, `methods`, `excludeBody=false`. |
| `CachingProperties` | `gateway.caching` | `enabled=false`, `provider="caffeine"`, `defaultTtl=86400` (1 day), `maxSize=10000`, `rules=[]`. Nested `CacheRuleConfig` holds `paths`, `methods`, `ttl`. |
| `CorsProperties` | `gateway.cors` | `enabled=true`, `allowedMethods=["GET","POST","PUT","PATCH","DELETE","OPTIONS"]`, `allowedHeaders=["*"]`, `maxAge=3600`, `allowCredentials=true`. |
| `SecurityProperties` | `gateway.security` | `enabled=false`, `guestAllowedPaths=[]`, nested `oauth2.{provider, client}` blocks. |

### `com.github.ifrugal.gateway.core.conman`

| Class | Notes |
|---|---|
| `ConmanProperties` (`gateway.conman`) | `enabled=false`, `servletUriMappings=["/mock/**"]`, `mappingFiles=["classpath:conman.yml"]`, `bannerPath="classpath:conman-banner.txt"`, `tenantIdHeader="tenant-id"`. The tenant header used by `ConmanHandler` is configurable via `gateway.conman.tenant-id-header` (was hardcoded prior to `1.1.0`). |
| `ConmanCache` | Holds `MockConfig` entries in a `ConcurrentHashMap` keyed by `{METHOD}_{URI}_{tenantId}`. Loaded eagerly from `mappingFiles` at startup; can be reloaded via the admin REST API. |
| `ConmanHandler` | Spring-managed reactive handler returning `Mono<ServerResponse>`, registered via a `RouterFunction` in `GatewayToolkitAutoConfiguration`. **Renamed from `ConmanServlet` in `1.1.0`** — the old name was historical and misleading. |
| `MockConfig` | YAML-bound POJO. Fields: `tenantId`, `tenantIds`, `request`, `response`. No `name` field. See [conman.md](conman.md) for the full structure. |
| `ConmanAdminController` | `@RestController` at `/conman/admin`. Endpoints: `POST /register` (multipart, max 1 MB), `GET /mocks`, `POST /reload`, `DELETE /mocks`, `GET /test`. |

### `com.github.ifrugal.gateway.core.conman.validation`

| Class | Notes |
|---|---|
| `RequestValidator` | Static utility. Validates request headers, query params, and body against the `MockConfig.RequestValidation` rules; bodies are validated with `networknt/json-schema-validator`. Always releases `DataBuffer` in a `finally`; times the body read out at 5 seconds. |

### `com.github.ifrugal.gateway.core.controller`

| Class | Notes |
|---|---|
| `CacheController` | `@RestController` at `/gateway/cache`. `@ConditionalOnBean(CacheProvider.class)` so it only loads when a cache is configured. Endpoints: `GET /{key}`, `POST /{key}?value=…&ttlSeconds=…`, `DELETE /{key}`, `GET /` (lists all keys with metadata), `DELETE /` (clears the cache). |

### `com.github.ifrugal.gateway.core.filter`

| Class | Notes |
|---|---|
| `LoggingAndCachingWebFilter` | The hot-path filter. `Ordered.HIGHEST_PRECEDENCE`. Decides per request whether to capture bodies (only when a logging rule with `excludeBody=false` or a cache rule matches), serves from cache on hit, logs the request/response on miss. |
| `BodyCaptureRequest` | `ServerHttpRequestDecorator`. Eagerly joins the request body into a cached `Mono<String>` so it can be replayed to downstream filters and read for logging. Currently no size cap — see the architecture review for known memory-pressure concerns. |
| `BodyCaptureResponse` | `ServerHttpResponseDecorator`. Accumulates response bytes into a `StringBuilder` for cache storage and logging. |
| `BodyCaptureExchange` | `ServerWebExchangeDecorator` that pairs the two wrappers above. |

### `com.github.ifrugal.gateway.core.filter.utils`

| Class | Notes |
|---|---|
| `RequestMatcher` | Static utility for Ant-style path matching + HTTP-method matching. Used by both logging-rule and caching-rule lookups. Not currently designed as an extension point — if you need custom matching logic, replace `LoggingAndCachingWebFilter` outright (it is `@ConditionalOnMissingBean`). |

## gateway-starter

Spring Boot starter. **No business logic** — every class here exists to wire `gateway-core` beans into a Spring application based on `gateway.*.enabled` flags.

| Class | Conditions | What it registers |
|---|---|---|
| `GatewayToolkitAutoConfiguration` | always loads (listed in `AutoConfiguration.imports`) | `CaffeineProvider` (when caching enabled + no other `CacheProvider` bean), `NoOpCacheProvider` (fallback, no other `CacheProvider` bean), `LoggingAndCachingWebFilter` (no existing bean), `CorsWebFilter` (when CORS enabled or `matchIfMissing`), nested `ConmanAutoConfiguration` (when Conman enabled — registers `ConmanCache`, `ConmanHandler`, and the `RouterFunction` that maps `servletUriMappings` to the handler). |
| `SecurityAutoConfiguration` | `@ConditionalOnClass(SecurityWebFilterChain.class)` + `@ConditionalOnProperty(... matchIfMissing=false)` | `SecurityWebFilterChain` with hardcoded `.authenticated()` for `/gateway/cache/**` and `/conman/admin/**`, hardcoded permits for the actuator + swagger paths, then user-supplied `guest-allowed-paths`. Also wires the Swagger UI `OpenAPI` bean when OAuth2 is enabled. |

### `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

```
com.github.ifrugal.gateway.autoconfigure.GatewayToolkitAutoConfiguration
com.github.ifrugal.gateway.autoconfigure.SecurityAutoConfiguration
```

This is what makes the starter "auto-configure" — Spring Boot reads the file at boot, instantiates each listed `@AutoConfiguration` class, and the per-bean `@ConditionalOn*` annotations decide what actually loads.

## gateway-app

Runnable demo + Docker image source.

- `GatewayApplication` — `@SpringBootApplication` with an `OperationCustomizer` that injects three documentation headers (`x-request-id`, `x-user-id`, `x-role`) into every OpenAPI operation. That's the entirety of the application code; everything else is wired by the starter.
- Profiles shipped as resources: `application.yml` (base), `application-eureka.yml`, `application-consul.yml`, `application-static.yml`. Activate via `--spring.profiles.active=eureka` (etc.) or `SPRING_PROFILES_ACTIVE`. There is no `application-docker.yml` inside this module — the Docker setup overlays its own file via `--spring.config.additional-location=file:/app/config/`, see [deployment.md](deployment.md).

## Extension points — quick reference

| What you want to do | How |
|---|---|
| Use a different cache backend | Register a `@Bean CacheProvider`. Caffeine bean steps aside via `@ConditionalOnMissingBean`. |
| Replace the body-capture filter | Register a `@Bean LoggingAndCachingWebFilter`. Default bean steps aside via `@ConditionalOnMissingBean`. |
| Add additional `WebFilter`s | Register them with a lower precedence than `Ordered.HIGHEST_PRECEDENCE` so they see the body-capture decorators. |
| Add your own configuration | Add a `@ConfigurationProperties("gateway.x")` class on the consumer side. The toolkit will not interfere. |
| Add custom REST endpoints | Standard Spring `@RestController` in your application. Keep them outside `/gateway/cache/**` and `/conman/admin/**` to avoid the hardcoded auth rules. |
| Customize OAuth2 protection | Set `gateway.security.enabled=false` and register your own `SecurityWebFilterChain` bean. The toolkit's chain is `@ConditionalOnMissingBean(SecurityWebFilterChain.class)`. |

## Build & test

| Command | Effect |
|---|---|
| `./mvnw -B clean verify` | Compile, run tests, generate JaCoCo coverage under each module's `target/site/jacoco/`. |
| `./mvnw -B clean install -pl gateway-core,gateway-starter` | Build only the library artefacts (skip the runnable app). |
| `./mvnw -B -DskipTests=true sonar:sonar` | Run SonarCloud analysis locally (requires `SONAR_TOKEN`). |
| `./mvnw -B org.apache.maven.plugins:maven-dependency-plugin:3.9.0:analyze` | Find undeclared/unused dependencies. The 3.9.0 pin is required for JDK 25 — earlier versions can't read the bytecode. |
