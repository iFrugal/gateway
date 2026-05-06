# TODO - Spring Gateway Toolkit

## Unit Tests (Priority: High)

### gateway-core
- [ ] `EnableGatewayToolkit` / `GatewayToolkitImportSelector` — verify annotation attributes propagate correctly
- [ ] `CachingProperties` — property binding, defaults, validation
- [ ] `CaffeineProvider` — put/get/invalidate, TTL expiry, cache miss behavior, clear
- [ ] `NoOpCacheProvider` — verify no-op behavior
- [ ] `LoggingAndCachingWebFilter` — cache hit short-circuit, cache miss with upstream, logging paths, ignored paths
- [ ] `BodyCaptureRequest` / `BodyCaptureResponse` — body capture and replay
- [ ] `RequestMatcher` — path matching, method matching, wildcard methods
- [ ] `LoggingProperties` — YAML binding, nested request config
- [ ] `CorsProperties` — defaults, YAML binding
- [ ] `ConmanProperties` — mock config binding, file path resolution
- [ ] `ConmanCache` — mock registration, lookup by method/uri/tenant, reload, clear
- [ ] `ConmanServlet` — request handling, mock response generation, template processing, not found, validation errors
- [ ] `ConmanAdminController` — CRUD endpoints for mock definitions
- [ ] `CacheController` — get/put/delete/list/clear cache entries
- [ ] `RequestValidator` — header validation, query param validation, body schema validation, empty body handling
- [ ] `MockConfig` — body resolution, template processing, bodyObj serialization

### gateway-starter
- [ ] `GatewayToolkitAutoConfiguration` — conditional bean creation, property-driven enablement
- [ ] `SecurityAutoConfiguration` — OAuth2 config, CSRF disabled, admin paths protected, guest paths permitted

### gateway-app
- [ ] `GatewayApplication` — Spring context loads successfully (integration test)

## Infrastructure
- [ ] Add JaCoCo coverage threshold (e.g., 80%)
- [ ] Add SonarCloud quality gate
- [ ] Publish first stable release to Maven Central
