# TODO - Spring Gateway Toolkit

## Unit Tests (Priority: High)

### gateway-core
- [ ] `EnableGatewayToolkit` / `GatewayToolkitImportSelector` — verify correct classes imported
- [ ] `CacheConfigProperties` — property binding, defaults, validation
- [ ] `CacheService` — put/get/evict, TTL expiry, cache miss behavior
- [ ] `CacheFilter` — cached response returned, cache bypass headers, non-GET skipped
- [ ] `GatewayConfigProperties` — YAML binding, nested route config
- [ ] `RouteLocatorConfig` — dynamic route creation from properties
- [ ] `LoggingConfig` — logger bean creation
- [ ] `OpenApiConfig` — Swagger grouped APIs from gateway routes
- [ ] `ConmanProperties` — mock config binding, file path resolution
- [ ] `ConmanService` — mock matching logic, fallback behavior, priority ordering
- [ ] `ConmanFilter` — request interception, mock response injection, passthrough when no match
- [ ] `ConmanController` — CRUD endpoints for mock definitions
- [ ] `RequestResponseUtils` — header extraction, body caching, request decoration
- [ ] `LoggingFilter` — request/response logged, sensitive headers masked, error paths
- [ ] `RequestLoggingFilter` — pre-filter logging, method/path/headers captured
- [ ] `ResponseLoggingFilter` — post-filter logging, status/body/timing captured
- [ ] `ModifyResponseBodyFilter` — body transformation, content-type handling
- [ ] `ConmanRequestValidator` — schema validation, required fields, error messages

### gateway-starter
- [ ] `GatewayToolkitAutoConfiguration` — conditional bean creation, property-driven enablement
- [ ] `SecurityAutoConfiguration` — OAuth2 config, CSRF, CORS, permit paths

### gateway-app
- [ ] `GatewayApplication` — Spring context loads successfully (integration test)

## Infrastructure
- [ ] Add JaCoCo coverage threshold (e.g., 80%)
- [ ] Add SonarCloud quality gate
- [ ] Publish to Maven Central (first release)
- [ ] Verify persistence-utils / app-building-commons are on Maven Central (remove JitPack if so)
