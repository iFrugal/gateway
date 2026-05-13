# Security Configuration Guide

## Overview

Spring Gateway Toolkit offers OAuth2 Resource Server (JWT) authentication, OAuth2 Login, path-based access control, and CORS configuration. All of it is configured via `gateway.security.*` properties and applied by `SecurityAutoConfiguration`.

> **Security is OFF by default.** `gateway.security.enabled` defaults to `false`. Putting the starter on the classpath does **not** automatically protect anything. If you also enable `gateway.caching.enabled=true` or `gateway.conman.enabled=true`, the resulting `/gateway/cache/**` and `/conman/admin/**` endpoints will be **publicly reachable** until you also flip `gateway.security.enabled=true`. Treat the two as paired.

## SecurityAutoConfiguration

`SecurityAutoConfiguration` is annotated with `@ConditionalOnClass(SecurityWebFilterChain.class)` and `@ConditionalOnProperty(prefix = "gateway.security", name = "enabled", havingValue = "true", matchIfMissing = false)`. Both conditions must be true:

- Spring Security must be on the classpath (`spring-boot-starter-security` is `optional` in `gateway-starter`; pull it in explicitly in your application's POM or use `gateway-app` which already declares it).
- `gateway.security.enabled` must be `true`. There is no `matchIfMissing` fallback — leaving the property unset leaves the gateway open.

### OAuth2 Support

The toolkit supports two OAuth2 flows:

**1. OAuth2 Resource Server (JWT)**
- Validates incoming JWT tokens from the Authorization header
- Configured via `spring.security.oauth2.resourceserver.jwt.*`
- Validates tokens against the configured JWK Set URI
- Suitable for API-to-API authentication

**2. OAuth2 Login**
- Enables user login via OAuth2 provider (e.g., Keycloak, Auth0, Google)
- Configured via `spring.security.oauth2.client.registration.main`
- Redirects successfully authenticated users to `/swagger-ui.html`
- Handles authentication entry points for protected resources

## SecurityProperties Configuration

Security settings are configured under the `gateway.security.*` namespace with the following structure:

```yaml
gateway:
  security:
    enabled: true                              # Enable/disable security
    guest-allowed-paths:                       # Public paths (no auth required)
      - /api/public/**
      - /search/**
    oauth2:
      enabled: true                            # Enable OAuth2 authentication
      provider:
        issuer-uri: https://auth.example.com
        authorization-uri: https://auth.example.com/oauth2/authorize
        token-uri: https://auth.example.com/oauth2/token
        jwk-set-uri: https://auth.example.com/.well-known/jwks.json
        user-info-uri: https://auth.example.com/oauth2/userInfo
        user-name-attribute: sub               # Default: "sub"
      client:
        id: ${OAUTH2_CLIENT_ID}
        secret: ${OAUTH2_CLIENT_SECRET}
        scopes: openid,profile,email
        redirect-uri: {baseUrl}/swagger-ui/oauth2-redirect.html
```

### SecurityProperties Fields

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | boolean | `false` | Enable or disable security configuration. **Default is OFF** — no auto-configuration happens until this is set to `true`. |
| `guest-allowed-paths` | List<String> | `[]` | Paths that bypass authentication |
| `oauth2.enabled` | boolean | `false` | Enable OAuth2 authentication |
| `oauth2.provider.issuer-uri` | String | - | OAuth2 issuer URI (e.g., Keycloak realm URL) |
| `oauth2.provider.authorization-uri` | String | - | Authorization endpoint URL |
| `oauth2.provider.token-uri` | String | - | Token endpoint URL |
| `oauth2.provider.jwk-set-uri` | String | - | JWK Set endpoint for token validation |
| `oauth2.provider.user-info-uri` | String | - | User info endpoint |
| `oauth2.provider.user-name-attribute` | String | `"sub"` | JWT claim to use as username |
| `oauth2.client.id` | String | - | OAuth2 client ID |
| `oauth2.client.secret` | String | - | OAuth2 client secret |
| `oauth2.client.scopes` | String | `openid,profile,email` | Comma-separated OAuth2 scopes |
| `oauth2.client.redirect-uri` | String | `{baseUrl}/swagger-ui/oauth2-redirect.html` | OAuth2 redirect URI |

## Path-Based Access Control

The security configuration implements a path-based access control strategy:

### Always-Protected Endpoints (when `gateway.security.enabled=true`)
- `/gateway/cache/**` - Cache management endpoints
- `/conman/admin/**` - Conman admin endpoints

These two patterns are added to the chain with `.authenticated()` **before** any `guest-allowed-paths` entries are applied, so an operator cannot accidentally permit-all them by including a prefix in `guest-allowed-paths`. This guarantee only kicks in when security is enabled — see the warning at the top of this page.

### Public Endpoints (hardcoded `.permitAll()`)

The following patterns are hardcoded into `SecurityAutoConfiguration` and **are not configurable**:

- `/`, `/actuator/health`, `/actuator/info`
- `/oauth2/**`, `/login/**`
- `/swagger-ui.html`, `/swagger-ui/**`, `/swagger-resources/**`
- `/v3/api-docs/**`, `/api-docs/**`, `/swagger-ui/oauth2-redirect.html`
- All HTTP `OPTIONS` requests (CORS preflight)

If you need a different actuator endpoint to be public (`/actuator/prometheus`, say), add it to `guest-allowed-paths`. If you need to **block** one of the hardcoded ones, run the gateway behind a reverse proxy that strips the path before it arrives — there is no in-process opt-out today.

### Guest-Allowed Paths
Add custom paths that should bypass authentication via `gateway.security.guest-allowed-paths`:

```yaml
gateway:
  security:
    guest-allowed-paths:
      - /api/public/**
      - /health
      - /documentation
```

### Default Behavior
All other paths require authentication when `gateway.security.enabled=true`.

## CorsProperties Configuration

CORS (Cross-Origin Resource Sharing) is configured under `gateway.cors.*`:

```yaml
gateway:
  cors:
    enabled: true
    allowed-origins:
      - http://localhost:3000
      - https://myapp.com
    allowed-methods:
      - GET
      - POST
      - PUT
      - PATCH
      - DELETE
      - OPTIONS
    allowed-headers:
      - "*"
    exposed-headers:
      - X-Custom-Header
    max-age: 3600
    allow-credentials: true
```

### CorsProperties Fields

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | boolean | `true` | Enable/disable CORS configuration |
| `allowed-origins` | List<String> | `[]` | Allowed origins |
| `allowed-methods` | List<String> | `[GET, POST, PUT, PATCH, DELETE, OPTIONS]` | Allowed HTTP methods |
| `allowed-headers` | List<String> | `["*"]` | Allowed request headers |
| `exposed-headers` | List<String> | `[]` | Headers exposed to clients |
| `max-age` | long | `3600` | Preflight cache duration (seconds) |
| `allow-credentials` | boolean | `true` | Allow credentials in requests |

## JWT claims → outbound headers

Once the JWT is validated, the toolkit can propagate selected claims to downstream services as plain headers — so each upstream microservice doesn't have to re-parse the token itself. Configure via `gateway.security.oauth2.claim-headers`. The filter (`JwtClaimsToHeadersWebFilter`) runs immediately after Spring Security's authentication step.

### Simple 1:1 mapping

The common case — each claim copies verbatim to a header:

```yaml
gateway:
  security:
    oauth2:
      claim-headers:
        - header: x-user-id
          claim: sub
        - header: x-tenant-id
          claim: tid
        - header: x-role
          claim: jobTitle
        - header: x-user-name
          claim: given_name
```

A claim that isn't present in the JWT skips the header. To inject a literal fallback instead:

```yaml
        - header: x-system-name
          claim: system            # absent on user-login JWTs
          default: nodeJs-backend   # used when claim missing
```

### Extract a substring from a claim (regex)

For issuer-URL-style claims that encode multiple pieces of information, use a regex with a named capture group:

```yaml
        - header: x-tenant-id
          claim: iss
          extract: "https://(?<tenantName>[^.]+)\\.b2clogin\\.com/tfp/(?<tenantId>[^/]+)/"
          named-group: tenantId

        - header: x-tenant-name
          claim: iss
          extract: "https://(?<tenantName>[^.]+)\\.b2clogin\\.com/tfp/(?<tenantId>[^/]+)/"
          named-group: tenantName
```

`extract` is a standard Java regex (`(?<name>...)` for named groups). If the regex doesn't match the claim value, the rule falls through to `default:` (if set) or skips the header.

If `named-group` is omitted, the entire matched substring is written.

### Fall back to a different claim

When the same logical value can come from two claims depending on which auth flow issued the JWT, chain two rules and mark the fallback as `if-previous-blank`:

```yaml
        - header: x-tenant-id
          claim: iss
          extract: "https://(?<t>[^.]+)\\.b2clogin\\.com/"
          named-group: t

        - header: x-tenant-id
          claim: tid                # standard Azure AD claim
          if-previous-blank: true   # only fires if the regex above produced nothing
          default: ""
```

### What's NOT supported in YAML

The following intentionally stay out of the YAML surface and need a custom `WebFilter` if you want them:

- **Conditional gating by flow** ("inject `x-role` only on user-login JWTs"). Use `if-previous-blank` for the simplest fallback shape; for richer logic, write a Spring `WebFilter` and register it instead of `JwtClaimsToHeadersWebFilter` (the bean is `@ConditionalOnMissingBean`).
- **Dictionary lookups** (e.g. tenant-id → tenant-code mapping). Org-specific; not part of OAuth2 claim semantics. Implement as a separate filter or as a step inside your custom one.

See [extension-points.md](extension-points.md) for the recipe.

### Operational notes

- **Regex compilation is eager.** Each `extract` pattern is compiled at application startup (via `SecurityProperties.OAuth2Config.compilePatterns()`). A malformed pattern fails the boot with `IllegalStateException("Invalid regex in gateway.security.oauth2.claim-headers entry for header '...'")`. The per-request hot path never compiles.
- **The filter is a pass-through when no rules are configured** — the empty list short-circuits and adds zero per-request cost.
- **Non-JWT requests are pass-throughs.** Anonymous requests, basic-auth requests, requests with the JWT validation already rejected — none of them get headers injected. The filter only fires when `ReactiveSecurityContextHolder` contains a `JwtAuthenticationToken`.

## Swagger UI Integration with OAuth2

When OAuth2 is enabled, the Swagger UI is automatically configured with OAuth2 authentication:

1. Swagger UI displays an "Authorize" button
2. Users can authenticate via the configured OAuth2 provider
3. Authorized tokens are automatically included in subsequent API requests
4. The redirect URI defaults to: `/swagger-ui/oauth2-redirect.html`

OpenAPI configuration is automatically generated with:
- OAuth2 security scheme
- Authorization code flow
- Configured scopes
- Authorization and token endpoints

## Environment Variable Equivalents

All security properties can be configured via environment variables using `UPPERCASE_SNAKE_CASE` format:

```bash
GATEWAY_SECURITY_ENABLED=true
GATEWAY_SECURITY_GUEST_ALLOWED_PATHS=/api/public/**,/health
GATEWAY_OAUTH2_ENABLED=true
OAUTH2_ISSUER_URI=https://auth.example.com
OAUTH2_CLIENT_ID=my-client-id
OAUTH2_CLIENT_SECRET=my-client-secret
OAUTH2_SCOPES=openid,profile,email
GATEWAY_CORS_ENABLED=true
GATEWAY_CORS_ORIGINS=http://localhost:3000,https://myapp.com
```

## Production Deployment Best Practices

### 1. JWT Token Validation
- Always use HTTPS in production
- Configure `oauth2.provider.jwk-set-uri` with the OAuth2 provider's JWKS endpoint
- The gateway validates token signatures and expiration automatically

### 2. Scope Configuration
- Define minimal required scopes in `oauth2.client.scopes`
- Document expected scopes in your API documentation
- Example: `openid,profile,email,api-access`

### 3. CORS Configuration
- Explicitly whitelist allowed origins (avoid using `*` in production)
- Restrict allowed methods to those needed by your application
- Configure exposed headers only for necessary custom headers

### 4. Guest-Allowed Paths
- Minimize public paths; use only for truly public endpoints
- Avoid exposing sensitive operations via guest paths
- Document public endpoints clearly

### 5. Admin Endpoints Protection
- `/gateway/cache/**` and `/conman/admin/**` require authentication
- Ensure only authorized personnel have credentials
- Monitor access to admin endpoints via application logs

### 6. OAuth2 Provider Configuration
- Use environment variables for sensitive credentials (`OAUTH2_CLIENT_SECRET`)
- Regularly rotate client credentials
- Monitor token refresh and expiration

### 7. Secrets Management
- Never commit OAuth2 credentials to version control
- Use a secrets vault (Vault, AWS Secrets Manager, etc.)
- Rotate credentials regularly according to security policies

### 8. Logging and Monitoring
- Enable audit logging for security events
- Monitor failed authentication attempts
- Alert on suspicious patterns
- Log configuration changes

### 9. SSL/TLS Configuration
Ensure proper SSL/TLS configuration in your Spring application:

```yaml
server:
  ssl:
    enabled: true
    key-store: classpath:keystore.jks
    key-store-password: ${KEYSTORE_PASSWORD}
    key-store-type: JKS
```

### 10. Testing Security Configuration
Test security rules with different user roles and paths:

```bash
# Test authenticated endpoint
curl -H "Authorization: Bearer YOUR_TOKEN" https://api.example.com/api/protected

# Test unauthenticated access
curl https://api.example.com/api/protected  # Should return 401

# Test guest paths
curl https://api.example.com/api/public  # Should return 200
```

## Troubleshooting

**Issue:** OAuth2 login redirects fail
- Verify `oauth2.provider.issuer-uri` matches your OAuth2 provider configuration
- Ensure client credentials are correct
- Check redirect URI matches OAuth2 provider settings

**Issue:** JWT token validation fails
- Verify `oauth2.provider.jwk-set-uri` is accessible and returns valid keys
- Check token claims include required fields
- Ensure token is not expired

**Issue:** CORS errors in browser
- Configure `gateway.cors.allowed-origins` with the client application's origin
- Set `gateway.cors.allow-credentials=true` if using cookies
- Verify `allowed-methods` includes the HTTP method being used
