# Troubleshooting

Common problems running Spring Gateway Toolkit, grouped by what you're seeing.

## Startup / classpath

### `BeanDefinitionStoreException: ... gateway.conman.servlet-uri-mappings must not be empty`

You set `gateway.conman.enabled: true` but cleared `servlet-uri-mappings` to an empty list (or set `mapping-files: []`). Both are `@NotEmpty` since `1.1.0`.

**Fix:** include at least one URI mapping and one mapping file, or disable Conman:

```yaml
gateway:
  conman:
    enabled: true
    servlet-uri-mappings: ["/mock/**"]
    mapping-files: [classpath:conman.yml]
```

### `IllegalArgumentException: gateway.caching.max-size must be at least 1` at startup

`CachingProperties.maxSize` is `@Min(1)`. You set it to `0` or a negative value.

**Fix:** set a positive integer, or set `gateway.caching.enabled: false` if you don't want caching at all.

### Application starts but no Conman / cache / security beans appear

The toolkit's auto-configuration loads on classpath presence, but each feature is gated on `gateway.<feature>.enabled`. **The annotation `@EnableGatewayToolkit` was removed in `1.1.0`** — set the YAML properties instead.

| Feature | Property | Default |
|---|---|---|
| Caching | `gateway.caching.enabled` | `false` |
| Conman | `gateway.conman.enabled` | `false` |
| Security | `gateway.security.enabled` | `false` |
| Logging | `gateway.logging.enabled` | `true` |
| CORS | `gateway.cors.enabled` | `true` |

### `ClassNotFoundException: org.springframework.security.web.server.SecurityWebFilterChain`

`SecurityAutoConfiguration` is `@ConditionalOnClass(SecurityWebFilterChain.class)` — it only loads when Spring Security is on the classpath. `gateway-starter` declares `spring-boot-starter-security` as **optional**, so you must pull it in explicitly:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
</dependency>
```

Or use `gateway-app` directly, which already declares both.

## Logging

### No log lines at all for some requests

Three likely causes, in order:

1. **`gateway.logging.enabled: false`** — flip to `true`.
2. **Path matches an ignore pattern.** Default ignore list: `/actuator/health`, `/actuator/health/ping`, `/swagger-ui/**`, `/v3/api-docs/**`, `/swagger-resources/**`. Set `gateway.logging.ignore-paths` to a different list if you want them logged.
3. **No `requests[]` rule matches the path/method.** The filter still emits the `Request` line, but the body is only captured when a rule matches with `exclude-body: false`. To log bodies for everything:
   ```yaml
   gateway:
     logging:
       requests:
         - paths: ["/**"]
           methods: ["*"]
           exclude-body: false
   ```

### Bodies show `...[truncated]` even though the actual payload is small

Misleading — the marker indicates the captured copy was capped, not the request. Check `gateway.logging.max-body-bytes` (default 64 KiB). Raise it if you legitimately log large payloads, lower it if you're memory-constrained. Setting it to `0` disables truncation entirely (heap-risky).

### `x-request-id` missing

The filter only adds the header on its outbound `chain.filter` mutation. If `gateway.logging.enabled: false`, the mutation doesn't happen and no `x-request-id` is generated. Either flip logging on or set the header from upstream.

### Sensitive header still appears in logs

Your secret header isn't in the redaction list. Defaults: `Authorization, Cookie, Set-Cookie, Proxy-Authorization, X-API-Key, X-Auth-Token`. Add your custom name:

```yaml
gateway:
  logging:
    sensitive-headers:
      - Authorization
      - Cookie
      - Set-Cookie
      - Proxy-Authorization
      - X-API-Key
      - X-Auth-Token
      - X-Tenant-Secret    # your custom one
```

Matching is case-insensitive. The original headers reach the upstream service unredacted; only the **logged copy** is masked.

## Caching

### Cache MISS on every request even though the rule clearly matches

Most common cause: **the response is not 2xx**. The filter only stores a response in cache when `exchange.getResponse().getStatusCode().is2xxSuccessful()` returns true. If the upstream returns 3xx redirects or 4xx errors, nothing gets cached.

Second most common: **the response body was empty or blank** when the cache rule ran. The filter only caches non-blank bodies. Pure-header endpoints (HEAD, 204) won't populate the cache.

### Two requests with different query-string orders served from the same cache entry

This is the intended behaviour. Cache keys sort query parameters alphabetically, so `?a=1&b=2` and `?b=2&a=1` produce the same key. If you genuinely want them distinct, you'll need to register a custom `CacheProvider` that overrides keying — see [extension-points.md](extension-points.md).

### Cached response served to the wrong user

The cache key is `METHOD:path?sortedQuery`. It deliberately does **not** include:

- Request headers (so `Accept-Language`, `Accept-Encoding` don't influence the key)
- Cookies / `Authorization` (so per-user content would be served across users)
- Tenant headers

**Do not cache endpoints whose responses depend on the caller's identity.** Restrict `gateway.caching.rules[].paths` to anonymous / shared content only.

### Stale data after upstream changed

In-memory Caffeine cache, per-instance. Restart the gateway — or invalidate via REST:

```bash
curl -X DELETE http://localhost:8080/gateway/cache/<METHOD:path?sortedQuery>
# or nuke everything:
curl -X DELETE http://localhost:8080/gateway/cache
```

Protect this endpoint with `gateway.security.enabled: true` in production.

## Security

### Admin endpoints (`/gateway/cache/**` or `/conman/admin/**`) reachable without auth

`gateway.security.enabled` defaults to `false`. Until you flip it, **everything is public**. Pair security with caching / Conman:

```yaml
gateway:
  security:
    enabled: true
    oauth2:
      enabled: true
      provider:
        issuer-uri: https://your-idp.example.com
```

### OAuth2 redirect loop on `/swagger-ui.html`

The default `oauth2.client.redirect-uri` is `{baseUrl}/swagger-ui/oauth2-redirect.html`. Your OAuth provider must register this exact URI as an allowed callback. If you're behind a reverse proxy with HTTPS termination, set `server.forward-headers-strategy: framework` so Spring resolves `{baseUrl}` against the original scheme/host.

### JWT validation fails with `An error occurred while attempting to decode the Jwt: Couldn't retrieve remote JWK set`

The configured `oauth2.provider.jwk-set-uri` is unreachable from the gateway pod. Check egress firewall rules; if you're behind a corporate proxy, set `-Dhttp.proxyHost` / `-Dhttps.proxyHost` JVM args.

### CORS preflight failures from a browser

Symptoms: browser console shows `Access-Control-Allow-Origin` mismatch on the `OPTIONS` call.

Causes, in order:

1. `gateway.cors.allowed-origins` is empty or doesn't include your front-end's origin (don't use `*` with credentials — browsers ignore `*` when `allow-credentials: true`).
2. Your front-end is sending a non-standard method that isn't in `allowed-methods`.
3. A reverse proxy in front of the gateway is stripping the CORS preflight `OPTIONS`.

## Conman (mock framework)

### `POST /conman/admin/register` returns 413 Payload Too Large before reaching the controller

This is the framework-level multipart cap doing its job. Either the upload is genuinely > 1 MB, or you're embedding `gateway-starter` in an app that doesn't have the multipart caps configured. Add to `application.yml`:

```yaml
spring:
  codec:
    max-in-memory-size: 1MB
  webflux:
    multipart:
      max-in-memory-size: 1MB
      max-disk-usage-per-part: 2MB
      max-parts: 8
```

If you legitimately need to register a bigger file, raise these AND `ConmanAdminController.MAX_UPLOAD_SIZE_BYTES`.

### Mock returns 404 even though the YAML clearly defines it

Check the cache key. Conman keys mocks as `{METHOD}_{URI}_{tenantId}`:

- `GET_/mock/users_null` if you set `tenantId: null` in the YAML.
- `GET_/mock/users_tenant-1` if you set `tenantId: tenant-1`.

The runtime header used for tenant resolution is `tenant-id` by default. If your request carries `tenant-1` in `tenant-id` but you defined the mock with `tenantId: null`, the lookup misses. Fix one or the other.

Use `GET /conman/admin/test?httpMethod=GET&uri=/mock/users&tenantId=tenant-1` to verify which entry (if any) matches without actually invoking it.

### `RuntimeException: Request body is required but Content-Length is 0`

Conman's `RequestValidator` requires `Content-Length > 0` when `bodySchema` is configured. Chunked-encoded requests without `Content-Length` are rejected. Either set `Content-Length` explicitly on your client, or drop the `bodySchema` from the mock if you don't actually need body validation.

### Template variable `${headers['X-Foo']}` renders as the literal string

The header lookup is case-sensitive in the template engine. Browsers/clients typically lowercase headers in transit, so `${headers['x-foo']}` is the safer form.

## CI / build

### Maven build fails with `Byte code of … is corrupt … Unsupported class file major version 69`

JaCoCo or `maven-dependency-plugin` is too old to read Java 25 bytecode. The toolkit pins:

- `jacoco.version` 0.8.14 (first version with ASM new enough for JDK 25)
- `maven-dependency-plugin:3.9.0` in the CI dependency-analyze job

If you've overridden either in your downstream POM, bump them.

### `Lombok TypeTag :: UNKNOWN` during compile

Your Lombok is older than 1.18.34 (first version with JDK 22+ support). The toolkit pins 1.18.46. If your project pins an older Lombok, bump it.

### Tests pass locally but CI fails

Most likely your local JDK is older than 25 (or, the rarer case, newer in a way that exposes a library bug). The CI matrix runs Corretto 25 specifically; install the same locally or use `./mvnw` (which respects toolchains) to match.

## Still stuck

- Check the [architecture review](https://github.com/iFrugal/gateway/blob/main/docs/architecture-overview.md) for the design rationale.
- Open an issue at https://github.com/iFrugal/gateway/issues with: full stack trace, `gateway.*` configuration, Spring Boot version, and `mvn dependency:tree | grep -E 'spring|caffeine|lazydevs'` output.
