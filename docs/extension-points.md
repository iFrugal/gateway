# Extension Points

Spring Gateway Toolkit ships sensible defaults, but every load-bearing bean is `@ConditionalOnMissingBean` so you can substitute your own. This page is the consolidated catalogue of override hooks. For the per-feature deep-dives see [caching.md](caching.md), [logging.md](logging.md), [conman.md](conman.md), [security.md](security.md).

## Mental model

Every extension follows the same Spring pattern:

1. Define your own `@Bean` of the appropriate type in your `@Configuration` class.
2. The toolkit's auto-configured bean steps aside because its declaration is guarded by `@ConditionalOnMissingBean`.
3. The rest of the toolkit continues to use the new bean transparently.

No SPI files, no service-loader registration, no toolkit-private APIs. If a toolkit class is `public`, you may extend or replace it.

## Replace the cache backend

The default `CaffeineProvider` is in-memory and single-instance. For shared / distributed caching, register your own `CacheProvider`:

```java
@Configuration
public class RedisCacheConfig {

    @Bean
    public CacheProvider cacheProvider(ReactiveRedisTemplate<String, String> redis) {
        return new RedisProvider(redis);
    }
}
```

Implement the interface with a **reactive** Redis client. Do not wrap a blocking `RedisTemplate` in `Mono.fromCallable` — that stalls Netty event-loop threads.

```java
public class RedisProvider implements CacheProvider {
    private final ReactiveRedisTemplate<String, String> redis;

    @Override
    public Mono<Optional<String>> get(String key) {
        return redis.opsForValue().get(key)
                .map(Optional::ofNullable)
                .defaultIfEmpty(Optional.empty());
    }

    @Override
    public Mono<Void> put(String key, String value, long ttlSeconds) {
        return redis.opsForValue()
                .set(key, value, Duration.ofSeconds(ttlSeconds))
                .then();
    }

    @Override
    public Mono<Void> invalidate(String key) {
        return redis.delete(key).then();
    }

    @Override
    public Mono<Void> clear() {
        // KEYS / SCAN over your key prefix; left as an exercise.
        return Mono.empty();
    }

    @Override
    public Map<String, Object> getInternalKeys() {
        // Optional: expose a snapshot for the /gateway/cache GET endpoint.
        return Map.of();
    }
}
```

The auto-configured Caffeine bean is gated on `@ConditionalOnMissingBean(CacheProvider.class)`; once your bean is present, it steps aside.

## Replace the logging-and-caching filter

`LoggingAndCachingWebFilter` is `@ConditionalOnMissingBean`. To customise it (different log format, different cache-key strategy, additional headers), provide your own:

```java
@Configuration
public class CustomFilterConfig {

    @Bean
    public LoggingAndCachingWebFilter loggingAndCachingWebFilter(
            LoggingProperties logging,
            CachingProperties caching,
            CacheProvider cacheProvider) {
        return new MyCustomFilter(logging, caching, cacheProvider);
    }
}
```

If your filter behaviour is **additive** (extra headers, extra log fields) rather than a replacement, register a *new* `WebFilter` at a lower precedence than `Ordered.HIGHEST_PRECEDENCE`:

```java
@Component
public class TraceIdEnricher implements WebFilter, Ordered {
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // toolkit's LoggingAndCachingWebFilter already ran; body-capture decorators are in place
        return chain.filter(exchange);
    }
}
```

## Replace the security chain

`SecurityAutoConfiguration` registers a `SecurityWebFilterChain` only when `gateway.security.enabled=true` AND no other `SecurityWebFilterChain` bean exists. To take over the entire authentication pipeline:

```java
@Configuration
@EnableWebFluxSecurity
public class CustomSecurityConfig {

    @Bean
    public SecurityWebFilterChain customSecurityChain(ServerHttpSecurity http) {
        return http
                .authorizeExchange(ex -> ex
                        .pathMatchers("/gateway/cache/**", "/conman/admin/**").hasRole("ADMIN")
                        .pathMatchers("/api/private/**").authenticated()
                        .anyExchange().permitAll())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .build();
    }
}
```

The toolkit's chain (which hardcodes `/gateway/cache/**` and `/conman/admin/**` as `.authenticated()`) is then skipped entirely. Just remember the hardcoded protection goes with it — you take on responsibility for the admin endpoints in your own chain.

## Custom request matching for cache / log rules

`RequestMatcher` is a package-level utility and not itself an extension point — it's used by the bundled filter. If you want different matching semantics (e.g. match on header values, JWT claims, or tenant ID), replace `LoggingAndCachingWebFilter` (see above) and route to your own matcher inside.

## Custom multipart caps for Conman uploads

The 1 MB cap in `ConmanAdminController` is `static final` and not overridable from YAML by design — it's a safety floor, not a tuning knob. The real tuning knobs are the framework-level multipart caps:

```yaml
spring:
  codec:
    max-in-memory-size: 4MB         # raise both numbers if you genuinely
  webflux:                          # need bigger admin uploads, but also
    multipart:                      # patch ConmanAdminController.MAX_UPLOAD_SIZE_BYTES
      max-in-memory-size: 4MB       # to match — the controller-level check
      max-disk-usage-per-part: 8MB  # is defence-in-depth and will reject
      max-parts: 8                  # the upload otherwise.
```

If 1 MB is genuinely too small for your workflow, the cleanest path is to fork `ConmanAdminController` and replace `MAX_UPLOAD_SIZE_BYTES`. Filing an issue with your use case is also welcome — we may make it configurable.

## OpenAPI / Swagger UI customisation

The `gateway-app` module ships a `customGlobalHeaders` `OperationCustomizer` bean that injects three header parameters (`x-request-id`, `x-user-id`, `x-role`) into every operation. To add your own, register another `OperationCustomizer`:

```java
@Bean
public OperationCustomizer myHeaders() {
    return (operation, handlerMethod) -> {
        operation.addParametersItem(new Parameter()
                .in("header").name("x-correlation-id").schema(new StringSchema()));
        return operation;
    };
}
```

When OAuth2 is enabled (`gateway.security.oauth2.enabled=true`), `SecurityAutoConfiguration` registers an `OpenAPI` bean wired up with the OAuth2 security scheme for Swagger UI's "Authorize" button. To customise the OAuth scopes shown there, replace the bean: it's `@ConditionalOnMissingBean(OpenAPI.class)` so any `OpenAPI` you register wins.

## Custom Conman handler

`ConmanHandler` itself is `@ConditionalOnMissingBean(ConmanHandler.class)`. To replace mock resolution (e.g. read mocks from a database, support a different YAML schema), provide your own:

```java
@Bean
public ConmanHandler conmanHandler(ConmanCache cache, ConmanProperties props) {
    return new DbBackedConmanHandler(myMockRepository, props);
}
```

The `RouterFunction` registered by `GatewayToolkitAutoConfiguration.conmanRoutes(...)` calls `conmanHandler::service` — your replacement only needs to expose a `Mono<ServerResponse> service(ServerRequest)` method (matching the existing signature).

## Custom config properties

For your own toolkit-adjacent settings, create a standard `@ConfigurationProperties` class in your application:

```java
@ConfigurationProperties(prefix = "myapp.gateway")
@Validated
@Data
public class MyAppGatewayProperties {
    @NotBlank
    private String upstreamSecret;
    @Min(1)
    private int retryAttempts = 3;
}
```

The toolkit will not interfere — its own `gateway.*` properties and your `myapp.gateway.*` properties coexist freely.

## Not an extension point: internal classes

These are `public` because Spring's wiring or cross-package usage in this same library requires it, but they should be treated as implementation details. They are marked with the project-local `@com.github.ifrugal.gateway.core.annotation.Internal` annotation (since `1.1.0`) and may change between minor — or even patch — versions:

- `BodyCaptureRequest`, `BodyCaptureResponse`, `BodyCaptureExchange` (`core.filter`)
- `LoggingAndCachingWebFilter` (`core.filter`) — replace it whole via `@ConditionalOnMissingBean` rather than subclass
- `RequestMatcher` (`core.filter.utils`)
- The `lazydevs.SerDe.YAML` adapter usage inside `ConmanCache`

API-audit tools (`revapi`, `apilyzer`, IntelliJ structural search) recognise the `Internal` naming convention and will warn or fail the build when downstream code subclasses or references annotated members. If you need behaviour from one of these, file an issue describing the use case rather than subclassing — we'd rather expose a deliberate hook than commit to the current shape.

## Quick reference

| What you want to do | Bean to register | The toolkit bean it replaces (`@ConditionalOnMissingBean`) |
|---|---|---|
| Use a different cache backend | `CacheProvider` | `CaffeineProvider` (or `NoOpCacheProvider`) |
| Replace the logging/caching filter | `LoggingAndCachingWebFilter` | itself |
| Replace the security chain | `SecurityWebFilterChain` | toolkit's chain in `SecurityAutoConfiguration` |
| Override the Conman reactive handler | `ConmanHandler` | itself |
| Override the Swagger UI OpenAPI bean | `OpenAPI` | toolkit's OAuth2-aware OpenAPI bean |
| Add fields to every OpenAPI operation | Additional `OperationCustomizer` | (additive — no replacement) |
