# Conman — Mock API Framework

Conman is a YAML-driven mock-API framework built into Spring Gateway Toolkit. It lets you stand up stubbed endpoints inside the same Gateway instance — useful for integration tests, contract development, and replacing flaky downstreams during development. Configuration is loaded from YAML files at startup and can be added/replaced at runtime via a REST admin API.

> **History note:** this class was named `ConmanServlet` prior to `1.1.0`. The name was historical and misleading — Spring Cloud Gateway is reactive, and this class has nothing to do with `jakarta.servlet.Servlet`. It is a `RouterFunction`-registered reactive handler returning `Mono<ServerResponse>`. Anyone holding a `1.0.x` binary reference to `ConmanServlet` needs to update imports.

## Configuration properties

All Conman properties live under `gateway.conman.*`:

```yaml
gateway:
  conman:
    enabled: false                              # default: false; flip to true to load Conman
    servlet-uri-mappings: ["/mock/**"]           # path patterns Conman handles
    mapping-files:                                # YAML files loaded at startup
      - classpath:conman.yml
    banner-path: classpath:conman-banner.txt    # ASCII banner shown in logs on startup
    tenant-id-header: tenant-id                 # request header used for tenant resolution
```

| Property | Default | Notes |
|---|---|---|
| `gateway.conman.enabled` | `false` | Auto-configuration is `@ConditionalOnProperty(havingValue="true")`. Without this flag no Conman beans load and no mock endpoints are exposed. |
| `gateway.conman.servlet-uri-mappings` | `["/mock/**"]` | Ant patterns registered with the `RouterFunction`. Requests not matching any pattern are routed normally. Validated as `@NotEmpty`. |
| `gateway.conman.mapping-files` | `["classpath:conman.yml"]` | Each entry is loaded via `lazydevs.SerDe.YAML.deserializeToList`. Treat the contents as trusted — see [security.md](security.md) for the SnakeYAML safety discussion. Validated as `@NotEmpty`. |
| `gateway.conman.banner-path` | `classpath:conman-banner.txt` | Optional ASCII banner. |
| `gateway.conman.tenant-id-header` | `"tenant-id"` | Request header consulted for tenant resolution. Hardcoded as `"tenant-id"` prior to `1.1.0`; now configurable. Blank values fall back to the default. Validated as `@NotBlank`. |

### Tenant header

The tenant identifier is read from the request header named by `gateway.conman.tenant-id-header` (default: `tenant-id`). Override it in YAML if your platform uses a different convention (e.g. `X-Tenant-Id`):

```yaml
gateway:
  conman:
    tenant-id-header: X-Tenant-Id
```

`ConmanHandler`'s legacy single-argument constructor still uses the default header for backwards compatibility; the Spring-managed bean wires through `ConmanProperties` automatically.

## Mock configuration file format

A mapping file is a **top-level YAML list** of mock entries. There is no wrapper key. Each entry is one `MockConfig`.

```yaml
# conman.yml — top-level list, no "mocks:" wrapper

- request:
    uri: /mock/users/{id}
    httpMethod: GET
    validation:
      headers:
        Authorization:
          required: true
      queryParams:
        id:
          required: true
  response:
    statusCode: 200
    contentType: application/json
    bodyTemplate: true
    body: |
      {
        "id": "${params.id}",
        "name": "John Doe",
        "tenant": "${headers['tenant-id']}"
      }
    responseHeaders:
      Cache-Control: "max-age=300"
  tenantId: tenant-1

- request:
    uri: /mock/orders
    httpMethod: POST
    validation:
      headers:
        Content-Type:
          required: true
      bodySchema: |
        {
          "type": "object",
          "properties": {
            "customerId": { "type": "integer" },
            "items": { "type": "array", "minItems": 1 }
          },
          "required": ["customerId", "items"]
        }
  response:
    statusCode: 201
    contentType: application/json
    body: |
      {"status": "created"}
  # Same mock served to multiple tenants:
  tenantIds: ["tenant-1", "tenant-2"]
```

### `MockConfig` fields

| Field | Type | Notes |
|---|---|---|
| `tenantId` | `String` | Single tenant this mock applies to. Use `null` (omit the field) for the default/no-tenant mock. |
| `tenantIds` | `Set<String>` | Multiple tenants for the same mock. Use this **or** `tenantId`, not both. |
| `request.uri` | `String` | Path the mock matches. Path variables in `{braces}` are exposed to templates as `${params.id}`. |
| `request.httpMethod` | `HttpMethod` | `GET`, `POST`, `PUT`, `DELETE`, etc. |
| `request.validation.headers` | `Map<String, Param>` | Each entry is `{ required: true|false, pattern: <regex> }`. Header names are case-sensitive in the validation map but matched case-insensitively at runtime. |
| `request.validation.queryParams` | `Map<String, Param>` | Same shape as `headers`. |
| `request.validation.bodySchema` | `String` (JSON Schema) | Inline JSON Schema string. JSON Schema Draft 4+ supported via `networknt/json-schema-validator`. |
| `request.validation.bodySchemaFile` | `String` | Path to a JSON Schema file; used if `bodySchema` is empty. |
| `response.statusCode` | `int` | HTTP status returned. |
| `response.contentType` | `String` | `Content-Type` header value. |
| `response.body` | `String` | Response body as a string. Often a JSON literal. |
| `response.bodyObj` | `Map<String, Object>` | Response body as an object; serialized to JSON. Use either this or `body`, not both. |
| `response.bodyTemplate` | `boolean` | When `true`, runs `body` (or the JSON-serialized `bodyObj`) through the template engine before returning. |
| `response.responseHeaders` | `Map<String, String>` | Headers added to the response. |

> **No `name` field.** Earlier versions of this doc showed `name: "Get User by ID"` on each mock; the field does not exist on `MockConfig`. Use a YAML comment for documentation instead.

## Response templates

When `response.bodyTemplate: true`, the response body is processed through a template engine before being returned. The substitution context is built from the incoming request:

| Token | Source |
|---|---|
| `${params.NAME}` | Path variable or query parameter named `NAME` |
| `${headers['Name']}` | Request header named `Name` (case-sensitive lookup) |
| `${body.field}` | JSON body field (dot notation for nested: `${body.user.name}`) |
| `${uuid1}` | A fresh `UUID.randomUUID()` injected per request |
| `${.now?string('yyyy-MM-dd HH:mm:ss')}` | Current timestamp (FreeMarker-style format string) |

Example:

```
GET /mock/users/123?role=admin
tenant-id: tenant-1
```

with template:

```json
{
  "userId": "${params.id}",
  "role": "${params.role}",
  "tenant": "${headers['tenant-id']}"
}
```

returns:

```json
{
  "userId": "123",
  "role": "admin",
  "tenant": "tenant-1"
}
```

## Multi-tenancy

`ConmanCache` keys mocks as `{METHOD}_{URI}_{tenantId}`:

- `GET_/mock/users_tenant-1` — tenant-specific mock
- `GET_/mock/users_null` — default mock for requests with no `tenant-id` header

`ConmanHandler` reads the `tenant-id` header on each incoming request and looks up the keyed entry. If you publish a mock with `tenantIds: ["tenant-1", "tenant-2"]`, the cache stores one entry per tenant; lookups for any other tenant fall through to the `null`-tenant mock if one exists, otherwise return 404.

## Admin REST API

Mounted at `/conman/admin/**` when `gateway.conman.enabled=true`. **These endpoints are public unless `gateway.security.enabled=true`** — `SecurityAutoConfiguration` adds them to its `authenticated()` list. Do not expose them on a public ingress without security.

| Method | Path | Body | Purpose |
|---|---|---|---|
| `POST` | `/conman/admin/register` | `multipart/form-data` with `registrationFile` part (and optional `tenantId` part); **max 1 MB** | Append mocks from an uploaded YAML to the in-memory cache. |
| `GET` | `/conman/admin/mocks` | — | Return all registered mocks keyed by their cache key. |
| `POST` | `/conman/admin/reload` | — | Clear the cache and reload from every file in `mapping-files`. |
| `DELETE` | `/conman/admin/mocks` | — | Clear all mocks from the cache. The `mapping-files` are *not* re-read; use `/reload` for that. |
| `GET` | `/conman/admin/test?httpMethod=GET&uri=/mock/users/1&tenantId=tenant-1` | — | Look up which mock would match the given request without actually invoking it. |

### Upload handling

`ConmanAdminController.register` accepts the upload as a reactive `org.springframework.http.codec.multipart.FilePart` (since `1.1.0`; `1.0.x` used servlet-API `MultipartFile`). The HTTP contract is identical — clients still send `multipart/form-data` with a `registrationFile` part and an optional `tenantId` part — but server-side the file content streams through the WebFlux codec instead of being buffered into a `MultipartFile` adapter.

The bundled `gateway-app/src/main/resources/application.yml` ships with the framework-level multipart caps already configured:

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

These ensure an oversized upload is rejected by the codec **before** it reaches `ConmanAdminController.register`. The controller's own 1 MB check (`ConmanAdminController.MAX_UPLOAD_SIZE_BYTES`) is a defence-in-depth secondary gate that fires *after* the join.

If you embed `gateway-starter` in your own Spring Boot application (rather than running `gateway-app`), copy the snippet above into your `application.yml`. Without it, an attacker uploading a 100 MB part can buffer the bytes before the controller-level check trips.

## Validation behaviour

| Outcome | HTTP response |
|---|---|
| Header validation: required header absent or pattern mismatch | `400 Bad Request` with the failing field name |
| Query-param validation: required param absent or pattern mismatch | `400 Bad Request` with the failing field name |
| Body schema validation failure | `400 Bad Request` with the `networknt` JSON Schema validation report |
| No mock found for `(method, uri, tenant-id)` | `404 Not Found` |
| All validations pass | Configured `response.statusCode` with template-processed body |

`RequestValidator` releases the request body's `DataBuffer` in a `finally` block (no resource leak) and times the body read out at 5 seconds.

## Known gaps

- **YAML deserialisation safety.** Mock files are deserialised through `lazydevs.SerDe.YAML.deserializeToList`. Treat all `mapping-files` and `/conman/admin/register` uploads as trusted input until that path is audited for SnakeYAML safe-constructor usage.

## Best practices

1. Keep `gateway.conman.enabled=false` in production unless you have a deliberate reason to expose mocks there. Use Spring profiles (`application-dev.yml`, `application-test.yml`) to opt in for the right environments.
2. Put `gateway.security.enabled=true` next to `gateway.conman.enabled=true` so the admin endpoints aren't reachable.
3. Use one YAML file per logical area of the API (`mappings-users.yml`, `mappings-orders.yml`) and list them all in `gateway.conman.mapping-files`. Smaller files are easier to review and reload.
4. Pin your templates with `bodyTemplate: true` only where you genuinely need substitution — static bodies render slightly faster and have no template-engine attack surface.
5. Test mocks via `GET /conman/admin/test` before going live; it verifies the cache key lookup without actually executing the response.
