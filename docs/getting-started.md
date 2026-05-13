# Getting Started — 5 minutes from zero to a working gateway

This walk-through gets you from an empty Spring Boot project to a Spring Cloud Gateway that logs every request and caches one route. By the end you'll have running JVM-side code and `curl` commands that demonstrate both features.

**You need:** Java 25 LTS, Maven 3.9+, ~5 minutes.

## 1. Create a Spring Boot project

Use [start.spring.io](https://start.spring.io) or `curl`:

```bash
curl -G https://start.spring.io/starter.tgz \
  -d type=maven-project \
  -d language=java \
  -d bootVersion=3.5.10 \
  -d javaVersion=25 \
  -d groupId=com.example \
  -d artifactId=hello-gateway \
  -d name=hello-gateway \
  -d packaging=jar \
  -d dependencies=cloud-gateway,webflux \
  -o hello-gateway.tgz
tar -xzf hello-gateway.tgz
cd hello-gateway
```

You now have a minimal reactive Gateway project. **Do not pick the servlet web starter** — Spring Cloud Gateway requires WebFlux.

## 2. Add the toolkit

In `pom.xml`, add the starter dependency next to the other Spring Boot dependencies:

```xml
<dependency>
    <groupId>com.github.ifrugal</groupId>
    <artifactId>gateway-starter</artifactId>
    <version>1.1.0</version>  <!-- or whatever is current on Maven Central -->
</dependency>
```

No annotation is required on your `@SpringBootApplication` class — the starter auto-configures from classpath presence.

## 3. Define one routed-and-cached endpoint

Edit `src/main/resources/application.yml`:

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: example-public-api
          uri: https://httpbin.org
          predicates:
            - Path=/api/anything/**
          filters:
            - StripPrefix=1

gateway:
  logging:
    enabled: true
    requests:
      - paths: ["/api/**"]
        methods: ["*"]
        exclude-body: false
  caching:
    enabled: true
    default-ttl: 60          # 60 seconds
    max-size: 1000
    rules:
      - paths: ["/api/anything/cached/**"]
        methods: [GET]
        ttl: 30              # override: 30 s for the /cached subtree
```

What this does:

- Every request to `/api/**` is logged with method, path, headers (Authorization redacted), and body (up to 64 KiB).
- GETs to `/api/anything/cached/**` are cached for 30 seconds; everything else passes through to `httpbin.org` unchanged.

## 4. Run it

```bash
./mvnw spring-boot:run
```

You should see Spring Boot start on port 8080.

## 5. Exercise it

In a second terminal:

```bash
# First call — cache MISS, hits httpbin
curl -i http://localhost:8080/api/anything/cached/hello

# Second call — cache HIT, served locally, no upstream call
curl -i http://localhost:8080/api/anything/cached/hello
```

Look at the response headers on the second call:

```
HTTP/1.1 200 OK
X-Cache: HIT
Content-Type: application/json
```

And in the gateway's log output you'll see a `Request:` line for each call but the second one returns instantly — no `Response:` entry from upstream because the filter short-circuited.

For a route that isn't cached:

```bash
# Always hits upstream
curl -i http://localhost:8080/api/anything/uncached
# (run twice — no X-Cache header on either response)
```

## 6. Inspect and manage the cache

The toolkit exposes a small REST surface for cache operations at `/gateway/cache`:

```bash
# List all cache keys with TTLs
curl -s http://localhost:8080/gateway/cache | jq

# Invalidate one entry
curl -X DELETE http://localhost:8080/gateway/cache/GET:/api/anything/cached/hello

# Clear everything
curl -X DELETE http://localhost:8080/gateway/cache
```

**In production**, set `gateway.security.enabled=true` to protect these endpoints — see [security.md](security.md).

## What just happened

- `gateway-starter`'s `GatewayToolkitAutoConfiguration` loaded automatically because of classpath presence.
- The `LoggingAndCachingWebFilter` registered at `Ordered.HIGHEST_PRECEDENCE` and now intercepts every request before Spring Cloud Gateway's routing.
- Caching is gated on the `gateway.caching.enabled: true` property; without it the filter still loads but its cache lookup is a no-op.
- The `CaffeineProvider` bean was wired in because `@ConditionalOnProperty(prefix="gateway.caching", name="enabled", havingValue="true")` + `@ConditionalOnMissingBean(CacheProvider.class)` both matched.

## Where to go next

| Goal | Doc |
|---|---|
| Cache something at a different TTL or only on specific HTTP methods | [caching.md](caching.md) |
| Log certain routes without bodies (e.g. login) | [logging.md](logging.md) |
| Stand up mock endpoints for integration tests | [conman.md](conman.md) |
| Add OAuth2 and lock down admin endpoints | [security.md](security.md) |
| Plug in a Redis cache provider, custom filter, etc. | [extension-points.md](extension-points.md) |
| Something didn't work | [troubleshooting.md](troubleshooting.md) |
| All the YAML properties | [configuration-reference.md](configuration-reference.md) |

## Minimum-effort production checklist

If you're moving this past your laptop:

1. Set `gateway.security.enabled: true` and configure OAuth2.
2. Pin `gateway.cors.allowed-origins` to your actual front-end hosts.
3. Set `gateway.caching.default-ttl` and per-rule TTLs based on real upstream cache-control headers — don't cache personal data.
4. Set `gateway.logging.max-body-bytes` lower (e.g. `4096`) if you have memory pressure.
5. Read [deployment.md](deployment.md) for the Docker / multi-profile story.
