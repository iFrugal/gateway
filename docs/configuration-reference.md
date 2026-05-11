# Configuration Reference

Complete configuration reference for all Spring Gateway Toolkit properties. All properties are organized by subsystem and include environment variable equivalents, default values, and descriptions.

## Configuration Properties by Subsystem

### Server Configuration

| YAML Property | Environment Variable | Default | Description |
|---|---|---|---|
| `server.port` | `SERVER_PORT` | `8080` | Server listening port |
| `spring.application.name` | `SPRING_APPLICATION_NAME` | `api-gateway` | Application name |

---

### Logging Configuration (gateway.logging.*)

Request and response logging for traffic flowing through the gateway. The filter is registered with `Ordered.HIGHEST_PRECEDENCE` and only wraps the exchange in body-capture decorators when a logging rule with `exclude-body: false` or a cache rule matches the request.

| YAML Property | Environment Variable | Default | Description |
|---|---|---|---|
| `gateway.logging.enabled` | `GATEWAY_LOGGING_ENABLED` | `true` | Enable/disable request/response logging |
| `gateway.logging.level` | `GATEWAY_LOGGING_LEVEL` | `info` | Log level (info, debug, warn, error) |
| `gateway.logging.ignore-paths` | `GATEWAY_LOGGING_IGNORE_PATHS` | `[]` | Path patterns to exclude from logging |
| `gateway.logging.requests[].paths` | - | - | Ant-style path patterns to match |
| `gateway.logging.requests[].methods` | - | - | HTTP methods (GET, POST, etc. or "*") |
| `gateway.logging.requests[].exclude-body` | - | `false` | Exclude request/response body from logs |

**Example Configuration:**
```yaml
gateway:
  logging:
    enabled: true
    level: info
    ignore-paths:
      - /actuator/health
      - /swagger-ui/**
    requests:
      - paths: ["/api/**"]
        methods: ["*"]
        exclude-body: false
      - paths: ["/api/auth/login"]
        methods: [POST]
        exclude-body: true
```

---

### Caching Configuration (gateway.caching.*)

Response caching with Caffeine provider support.

| YAML Property | Environment Variable | Default | Description |
|---|---|---|---|
| `gateway.caching.enabled` | `GATEWAY_CACHING_ENABLED` | `false` | Enable/disable response caching |
| `gateway.caching.provider` | `GATEWAY_CACHING_PROVIDER` | `caffeine` | Cache provider name. Only `caffeine` is shipped in-tree. The `CacheProvider` interface is an SPI — register a `@Bean` of type `CacheProvider` to substitute another backend (Redis, Hazelcast, etc.); the auto-configured Caffeine bean is gated on `@ConditionalOnMissingBean`. |
| `gateway.caching.default-ttl` | `GATEWAY_CACHE_DEFAULT_TTL` | `86400` | Default TTL in seconds (1 day) |
| `gateway.caching.max-size` | `GATEWAY_CACHE_MAX_SIZE` | `10000` | Maximum cache entries |
| `gateway.caching.rules[].paths` | - | - | Ant-style path patterns |
| `gateway.caching.rules[].methods` | - | - | HTTP methods (GET, POST, etc. or "*") |
| `gateway.caching.rules[].ttl` | - | - | TTL in seconds (overrides default-ttl) |

**Example Configuration:**
```yaml
gateway:
  caching:
    enabled: true
    provider: caffeine
    default-ttl: 86400
    max-size: 10000
    rules:
      - paths: ["/api/products", "/api/categories"]
        methods: [GET]
        ttl: 3600
      - paths: ["/api/inventory"]
        methods: ["*"]
        ttl: 1800
```

---

### CORS Configuration (gateway.cors.*)

Cross-Origin Resource Sharing configuration for browser-based clients.

| YAML Property | Environment Variable | Default | Description |
|---|---|---|---|
| `gateway.cors.enabled` | `GATEWAY_CORS_ENABLED` | `true` | Enable/disable CORS |
| `gateway.cors.allowed-origins` | `GATEWAY_CORS_ORIGINS` | `[]` | Comma-separated allowed origins |
| `gateway.cors.allowed-methods` | - | `GET,POST,PUT,PATCH,DELETE,OPTIONS` | Allowed HTTP methods |
| `gateway.cors.allowed-headers` | - | `*` | Allowed request headers |
| `gateway.cors.exposed-headers` | - | `[]` | Headers exposed to clients |
| `gateway.cors.max-age` | `GATEWAY_CORS_MAX_AGE` | `3600` | Preflight cache duration (seconds) |
| `gateway.cors.allow-credentials` | `GATEWAY_CORS_ALLOW_CREDENTIALS` | `true` | Allow credentials in CORS requests |

**Example Configuration:**
```yaml
gateway:
  cors:
    enabled: true
    allowed-origins:
      - http://localhost:3000
      - https://myapp.com
      - https://admin.example.com
    allowed-methods:
      - GET
      - POST
      - PUT
      - DELETE
      - OPTIONS
    allowed-headers:
      - "*"
    exposed-headers:
      - X-Total-Count
      - X-Page-Number
    max-age: 3600
    allow-credentials: true
```

---

### Security Configuration (gateway.security.*)

OAuth2 authentication, path-based access control, and JWT validation.

| YAML Property | Environment Variable | Default | Description |
|---|---|---|---|
| `gateway.security.enabled` | `GATEWAY_SECURITY_ENABLED` | `false` | Enable/disable security |
| `gateway.security.guest-allowed-paths` | `GATEWAY_SECURITY_GUEST_ALLOWED_PATHS` | `[]` | Paths that bypass authentication |
| `gateway.security.oauth2.enabled` | `GATEWAY_OAUTH2_ENABLED` | `false` | Enable OAuth2 authentication |
| `gateway.security.oauth2.provider.issuer-uri` | `OAUTH2_ISSUER_URI` | - | OAuth2 issuer URI |
| `gateway.security.oauth2.provider.authorization-uri` | `OAUTH2_AUTHORIZATION_URI` | - | Authorization endpoint URL |
| `gateway.security.oauth2.provider.token-uri` | `OAUTH2_TOKEN_URI` | - | Token endpoint URL |
| `gateway.security.oauth2.provider.jwk-set-uri` | `OAUTH2_JWK_SET_URI` | - | JWK Set endpoint for token validation |
| `gateway.security.oauth2.provider.user-info-uri` | `OAUTH2_USER_INFO_URI` | - | User info endpoint |
| `gateway.security.oauth2.provider.user-name-attribute` | `OAUTH2_USER_NAME_ATTRIBUTE` | `sub` | JWT claim for username |
| `gateway.security.oauth2.client.id` | `OAUTH2_CLIENT_ID` | - | OAuth2 client ID |
| `gateway.security.oauth2.client.secret` | `OAUTH2_CLIENT_SECRET` | - | OAuth2 client secret |
| `gateway.security.oauth2.client.scopes` | `OAUTH2_SCOPES` | `openid,profile,email` | OAuth2 scopes (comma-separated) |
| `gateway.security.oauth2.client.redirect-uri` | - | `{baseUrl}/swagger-ui/oauth2-redirect.html` | OAuth2 redirect URI |

**Access Control Rules (hardcoded in `SecurityAutoConfiguration`):**
- **Always Protected** (cannot be overridden by `guest-allowed-paths`): `/gateway/cache/**`, `/conman/admin/**`
- **Always Public** (hardcoded `.permitAll()` list): `/`, `/actuator/health`, `/actuator/info`, `/oauth2/**`, `/login/**`, `/swagger-ui.html`, `/swagger-ui/**`, `/swagger-resources/**`, `/v3/api-docs/**`, `/api-docs/**`, `/swagger-ui/oauth2-redirect.html`, and all `OPTIONS` requests (for CORS preflight)
- **Guest Paths:** Whatever you put under `guest-allowed-paths` is added to the public list at startup
- **All Other Paths:** Require authentication

**Example Configuration:**
```yaml
gateway:
  security:
    enabled: true
    guest-allowed-paths:
      - /api/public/**
      - /search
      - /documentation
    oauth2:
      enabled: true
      provider:
        issuer-uri: https://auth.example.com
        authorization-uri: https://auth.example.com/oauth2/authorize
        token-uri: https://auth.example.com/oauth2/token
        jwk-set-uri: https://auth.example.com/.well-known/jwks.json
        user-info-uri: https://auth.example.com/oauth2/userInfo
        user-name-attribute: sub
      client:
        id: ${OAUTH2_CLIENT_ID}
        secret: ${OAUTH2_CLIENT_SECRET}
        scopes: openid,profile,email,api-access
```

---

### Conman Mock API Framework (gateway.conman.*)

Configuration for the Conman mock API framework for testing and development.

| YAML Property | Environment Variable | Default | Description |
|---|---|---|---|
| `gateway.conman.enabled` | `GATEWAY_CONMAN_ENABLED` | `false` | Enable/disable Conman mock APIs. Auto-configuration is `@ConditionalOnProperty(havingValue="true")` — if unset, no Conman beans load. |
| `gateway.conman.servlet-uri-mappings` | `GATEWAY_CONMAN_SERVLET_URI_MAPPINGS` | `[/mock/**]` | Ant patterns the Conman reactive handler intercepts. |
| `gateway.conman.mapping-files` | - | `[classpath:conman.yml]` | YAML files loaded at startup. Each file is a top-level list of `MockConfig` entries — see [conman.md](conman.md). |
| `gateway.conman.banner-path` | - | `classpath:conman-banner.txt` | Optional ASCII banner shown in logs on startup. |

> **Tenant header is hardcoded.** Conman reads the tenant from the literal request header `tenant-id` — there is no `gateway.conman.tenant-id-header` property today. Setting one in YAML has no effect. See [conman.md — Known gaps](conman.md#known-gaps).

**Example Configuration:**
```yaml
gateway:
  conman:
    enabled: true
    servlet-uri-mappings:
      - /mock/**
      - /api/mock/**
    mapping-files:
      - classpath:conman.yml
      - classpath:conman/users-api.yml
      - classpath:conman/products-api.yml
    banner-path: classpath:conman-banner.txt
```

---

### Spring Cloud Gateway Configuration (spring.cloud.gateway.*)

Core gateway routing and discovery configuration.

| YAML Property | Environment Variable | Default | Description |
|---|---|---|---|
| `spring.cloud.gateway.discovery.locator.enabled` | `GATEWAY_DISCOVERY_ENABLED` | `false` | Enable service discovery-based routing |
| `spring.cloud.gateway.discovery.locator.lower-case-service-id` | - | `true` | Convert service IDs to lowercase |
| `spring.cloud.gateway.routes` | - | `[]` | Route definitions |

**Example Configuration:**
```yaml
spring:
  cloud:
    gateway:
      discovery:
        locator:
          enabled: true
          lower-case-service-id: true
      routes:
        - id: users-service
          uri: lb://users-service
          predicates:
            - Path=/api/users/**
          filters:
            - StripPrefix=2
        - id: products-service
          uri: lb://products-service
          predicates:
            - Path=/api/products/**
```

---

### OAuth2 Registration (spring.security.oauth2.client.*)

Spring Security OAuth2 client registration for OAuth2 Login functionality.

| YAML Property | Environment Variable | Default | Description |
|---|---|---|---|
| `spring.security.oauth2.client.registration.main.client-id` | `OAUTH2_CLIENT_ID` | - | OAuth2 client ID |
| `spring.security.oauth2.client.registration.main.client-secret` | `OAUTH2_CLIENT_SECRET` | - | OAuth2 client secret |
| `spring.security.oauth2.client.registration.main.client-name` | - | `OAuth2 Provider` | Display name for OAuth2 provider |
| `spring.security.oauth2.client.registration.main.provider` | - | `main` | Provider configuration name |
| `spring.security.oauth2.client.registration.main.scope` | `OAUTH2_SCOPES` | `openid,profile,email` | OAuth2 scopes |
| `spring.security.oauth2.client.registration.main.redirect-uri` | - | `{baseUrl}/swagger-ui/oauth2-redirect.html` | OAuth2 redirect URI |
| `spring.security.oauth2.client.registration.main.authorization-grant-type` | - | `authorization_code` | Grant type |

---

### OAuth2 Provider (spring.security.oauth2.client.provider.*)

OAuth2 provider endpoint configuration.

| YAML Property | Environment Variable | Default | Description |
|---|---|---|---|
| `spring.security.oauth2.client.provider.main.issuer-uri` | `OAUTH2_ISSUER_URI` | - | OAuth2 issuer URI |
| `spring.security.oauth2.client.provider.main.authorization-uri` | `OAUTH2_AUTHORIZATION_URI` | - | Authorization endpoint |
| `spring.security.oauth2.client.provider.main.token-uri` | `OAUTH2_TOKEN_URI` | - | Token endpoint |
| `spring.security.oauth2.client.provider.main.jwk-set-uri` | `OAUTH2_JWK_SET_URI` | - | JWK Set endpoint |
| `spring.security.oauth2.client.provider.main.user-info-uri` | `OAUTH2_USER_INFO_URI` | - | User info endpoint |
| `spring.security.oauth2.client.provider.main.user-name-attribute` | `OAUTH2_USER_NAME_ATTRIBUTE` | `sub` | JWT claim for username |

---

### Resource Server (spring.security.oauth2.resourceserver.jwt.*)

JWT token validation configuration for resource servers.

| YAML Property | Environment Variable | Default | Description |
|---|---|---|---|
| `spring.security.oauth2.resourceserver.jwt.issuer-uri` | `OAUTH2_ISSUER_URI` | - | Token issuer URI |
| `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` | `OAUTH2_JWK_SET_URI` | - | JWK Set URI for signature verification |

---

### Swagger/SpringDoc Configuration (springdoc.*)

OpenAPI documentation and Swagger UI configuration.

| YAML Property | Environment Variable | Default | Description |
|---|---|---|---|
| `springdoc.swagger-ui.path` | - | `/swagger-ui.html` | Swagger UI path |
| `springdoc.swagger-ui.persist-authorization` | - | `true` | Persist authorization tokens |
| `springdoc.swagger-ui.urls-primary-name` | - | `API Gateway` | Primary API name in Swagger UI |
| `springdoc.api-docs.enabled` | - | `true` | Enable OpenAPI docs |
| `springdoc.api-docs.groups.enabled` | - | `true` | Enable API groups |
| `springdoc.packages-to-scan` | - | `com.github.ifrugal.gateway` | Packages to scan for OpenAPI annotations |

---

### Actuator Configuration (management.*)

Application monitoring and metrics endpoints.

| YAML Property | Environment Variable | Default | Description |
|---|---|---|---|
| `management.endpoints.web.exposure.include` | - | `health,info,metrics,caches` | Exposed actuator endpoints |
| `management.endpoint.health.show-details` | - | `always` | Health endpoint detail level |
| `management.metrics.enabled` | - | `true` | Enable metrics collection |
| `management.endpoint.caches.enabled` | - | `true` | Enable cache statistics endpoint |

---

### Application Logging (logging.*)

Root logger configuration for the application.

| YAML Property | Environment Variable | Default | Description |
|---|---|---|---|
| `logging.level.root` | `LOG_LEVEL_ROOT` | `INFO` | Root logger level |
| `logging.level.org.springframework.cloud.gateway` | `GATEWAY_LOG_LEVEL` | `WARN` | Spring Cloud Gateway logger level |
| `logging.level.com.github.ifrugal.gateway` | `TOOLKIT_LOG_LEVEL` | `INFO` | Toolkit logger level |

---

## Environment Variable Naming Convention

Environment variables use `UPPERCASE_SNAKE_CASE` format:

- YAML dots (`.`) → underscores (`_`)
- YAML hyphens (`-`) → underscores (`_`)
- List items: use commas or repeat the variable with index

**Examples:**
```
gateway.logging.enabled → GATEWAY_LOGGING_ENABLED
gateway.caching.max-size → GATEWAY_CACHING_MAX_SIZE
gateway.security.guest-allowed-paths → GATEWAY_SECURITY_GUEST_ALLOWED_PATHS (comma-separated)
spring.application.name → SPRING_APPLICATION_NAME
```

---

## Quick Start Examples

### Minimal Configuration (No Security)
```yaml
server:
  port: 8080

gateway:
  logging:
    enabled: true
  caching:
    enabled: false
  cors:
    enabled: true
  security:
    enabled: false
```

### Development Configuration (OAuth2 + Caching)
```yaml
server:
  port: 8080

gateway:
  logging:
    enabled: true
    level: debug
  caching:
    enabled: true
    default-ttl: 3600
  cors:
    enabled: true
    allowed-origins: [http://localhost:3000]
  security:
    enabled: true
    guest-allowed-paths: [/health, /search]
    oauth2:
      enabled: true
      provider:
        issuer-uri: ${OAUTH2_ISSUER_URI}
      client:
        id: ${OAUTH2_CLIENT_ID}
        secret: ${OAUTH2_CLIENT_SECRET}
```

### Production Configuration (Full Security)
```yaml
server:
  port: 8443
  ssl:
    enabled: true
    key-store: /secrets/keystore.jks
    key-store-password: ${KEYSTORE_PASSWORD}

gateway:
  logging:
    enabled: true
    level: info
  caching:
    enabled: true
    default-ttl: 86400
  cors:
    enabled: true
    allowed-origins:
      - https://myapp.com
      - https://admin.myapp.com
  security:
    enabled: true
    oauth2:
      enabled: true
      provider:
        issuer-uri: ${OAUTH2_ISSUER_URI}
        jwk-set-uri: ${OAUTH2_JWK_SET_URI}
      client:
        id: ${OAUTH2_CLIENT_ID}
        secret: ${OAUTH2_CLIENT_SECRET}
        scopes: openid,profile,email,api-access
```

---

## Configuration Validation

Always verify:
- Required OAuth2 URIs are accessible
- Client ID/secret are correct
- CORS origins include client domains
- Guest paths don't expose sensitive endpoints
- Cache TTL is appropriate for your data
- Log levels don't expose sensitive information
