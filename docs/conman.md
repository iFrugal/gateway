# Conman Mock API Framework

Conman is a powerful mock API framework integrated into Spring Gateway Toolkit that enables rapid testing and development by providing configurable mock endpoints. It allows teams to define mock API responses without requiring actual backend services.

## Overview

The Conman framework provides a flexible mechanism for intercepting HTTP requests and returning pre-configured mock responses. It supports multi-tenant architectures, request validation, dynamic response templates, and hot-reloading of mock configurations via YAML files.

## Architecture Components

### ConmanProperties

Configuration properties for Conman are defined under the `gateway.conman.*` namespace:

```properties
# Enable/disable Conman mock API framework
gateway.conman.enabled=true

# Servlet URI mappings (paths to intercept)
gateway.conman.servletUriMappings=/api/mocks/**,/mock/**

# Configuration file locations
gateway.conman.mappingFiles=classpath:conman-mocks.yaml,file:/etc/conman/custom-mocks.yaml

# HTTP header used to identify tenants
gateway.conman.tenantIdHeader=X-Tenant-Id
```

### ConmanCache

An in-memory cache that stores mock configurations using `ConcurrentHashMap`. Cache keys follow the pattern:

```
{METHOD}_{URI}_{tenantId}
```

Example keys:
- `GET_/api/users_tenant-1`
- `POST_/api/orders_null` (for default tenant)

The cache stores `MockConfig` objects for fast lookup during request processing.

### MockConfig

Defines a complete mock endpoint configuration:

```yaml
request:
  uri: /api/users/{id}
  httpMethod: GET
  validation:
    headerValidation:
      Authorization: required
      X-API-Key: required
    paramValidation:
      id: integer
    bodySchema: |
      {
        "type": "object",
        "properties": {
          "name": {"type": "string"},
          "age": {"type": "integer"}
        },
        "required": ["name"]
      }

response:
  statusCode: 200
  contentType: application/json
  body: |
    {
      "id": "${params.id}",
      "name": "${body.name}",
      "createdBy": "${headers['X-User-Id']}"
    }
  responseHeaders:
    X-Total-Count: "100"
    Cache-Control: "max-age=3600"

tenantId: tenant-1
```

### RequestValidation

Supports three levels of request validation:

**Header Validation:** Ensures required headers are present
- Validates header existence and can check values
- Marked as `required` or `optional`

**Parameter Validation:** Validates query/path parameters
- Type checking: `string`, `integer`, `boolean`, etc.
- Pattern matching using regex
- Min/max constraints

**Body Schema:** JSON Schema validation via `networknt/json-schema-validator`
- Full JSON Schema Draft 4+ support
- Complex validation rules
- Nested object validation

### Response Features

**Dynamic Templates:** Use request context for dynamic responses
- `${params.paramName}` - access query/path parameters
- `${headers['Header-Name']}` - access request headers
- `${body.fieldName}` - access request body fields

**Status Codes & Headers:** Configure HTTP response metadata
- Custom HTTP status codes
- Response headers for CORS, caching, etc.

**Content Types:** Support for JSON, XML, plain text, etc.

## ConmanServlet

The servlet handler that processes incoming requests:

1. Intercepts HTTP requests matching configured URI patterns
2. Normalizes request details (method, path, tenant)
3. Looks up `MockConfig` from cache using METHOD_URI_tenantId key
4. Validates request against configured validation rules
5. If validation fails, returns 400 Bad Request with error details
6. Processes response templates with request context
7. Returns mock response with configured status code and headers

Tenant resolution order:
1. Check `X-Tenant-Id` header (configurable)
2. Fallback to `null` tenant for default mocks

## ConmanAdminController

REST API for managing mock configurations at `/conman/admin`:

### Register Endpoint
```
POST /conman/admin/register
Content-Type: multipart/form-data

Parameters:
- file: YAML configuration file (max 1MB)
- tenantId: (optional) tenant context
```

Uploads and registers mock configurations from YAML files.

### List All Mocks
```
GET /conman/admin/getAllMocks
```

Returns all registered mock configurations with their keys.

### Reload Configurations
```
POST /conman/admin/reload
```

Clears cache and reloads from configured mapping files. Useful for development.

### Clear All
```
POST /conman/admin/clearAll
```

Removes all mock configurations from cache.

### Test Mock
```
POST /conman/admin/testMock
Content-Type: application/json

Body:
{
  "method": "GET",
  "uri": "/api/users/123",
  "tenantId": "tenant-1",
  "headers": {"Authorization": "Bearer token"},
  "params": {"filter": "active"},
  "body": {"name": "John"}
}
```

Tests a mock endpoint before deployment.

## Configuration by YAML

### Complete Example

```yaml
mocks:
  - name: "Get User by ID"
    request:
      uri: /api/users/{id}
      httpMethod: GET
      validation:
        headerValidation:
          Authorization: required
        paramValidation:
          id: integer
    response:
      statusCode: 200
      contentType: application/json
      body: |
        {
          "id": "${params.id}",
          "name": "John Doe",
          "email": "john@example.com",
          "tenant": "${headers['X-Tenant-Id']}"
        }
      responseHeaders:
        X-Request-ID: "req-${params.id}"
        Cache-Control: "max-age=300"
    tenantIds: ["tenant-1", "tenant-2"]

  - name: "Create Order"
    request:
      uri: /api/orders
      httpMethod: POST
      validation:
        headerValidation:
          Content-Type: required
          Authorization: required
        bodySchema: |
          {
            "type": "object",
            "properties": {
              "customerId": {"type": "integer"},
              "items": {
                "type": "array",
                "minItems": 1
              }
            },
            "required": ["customerId", "items"]
          }
    response:
      statusCode: 201
      contentType: application/json
      body: |
        {
          "orderId": "ORD-${params.orderId}",
          "customerId": "${body.customerId}",
          "status": "pending",
          "createdAt": "2026-02-25T12:00:00Z"
        }
      responseHeaders:
        Location: "/api/orders/ORD-${params.orderId}"
    tenantId: null

  - name: "Validation Error Response"
    request:
      uri: /api/products
      httpMethod: GET
      validation:
        paramValidation:
          limit: integer
          offset: integer
    response:
      statusCode: 200
      contentType: application/json
      body: |
        {
          "products": [],
          "limit": "${params.limit}",
          "offset": "${params.offset}"
        }
    tenantIds: ["tenant-1"]
```

## Multi-Tenant Support

Conman supports multiple tenants with the following resolution strategy:

1. **Tenant-Aware Lookups:** Cache keys include tenant identifier
2. **Header-Based Tenant Resolution:** Uses configurable header (default: `X-Tenant-Id`)
3. **Tenant Fallback:** If tenant-specific mock not found, falls back to `null` tenant
4. **Multiple Tenant IDs:** Single mock can serve multiple tenants via `tenantIds` list

Example:
- Request to `/api/users` with `X-Tenant-Id: tenant-1` uses `GET_/api/users_tenant-1`
- Request without tenant header uses `GET_/api/users_null`

## Template System

Response bodies support variable substitution using `${}` syntax:

- **`${params.NAME}`** - Query/path parameter value
- **`${headers['Name']}`** - HTTP request header (case-sensitive)
- **`${body.FIELD}`** - JSON body field (nested with dot notation: `${body.user.name}`)

Example request:
```
GET /api/users/123?role=admin
X-User-Id: user-456
Content-Type: application/json

{"department": "engineering"}
```

Template:
```json
{
  "userId": "${params.id}",
  "role": "${params.role}",
  "createdBy": "${headers['X-User-Id']}",
  "dept": "${body.department}"
}
```

Result:
```json
{
  "userId": "123",
  "role": "admin",
  "createdBy": "user-456",
  "dept": "engineering"
}
```

## Validation Behavior

- **Invalid Configuration:** Returns 400 Bad Request with validation error details
- **Missing Required Headers:** Request rejected with descriptive error message
- **Schema Validation Failure:** Returns 400 with JSON Schema validation report
- **Successful Validation:** Proceeds to response generation

## Best Practices

1. **Organize by Feature:** Group related mocks in separate YAML files
2. **Consistent Naming:** Use clear, descriptive mock names for debugging
3. **Validation Coverage:** Always validate headers, params, and body for robustness
4. **Dynamic Responses:** Use templates to make mocks feel realistic
5. **Tenant Isolation:** Properly configure tenant-specific mocks when needed
6. **Version Control:** Keep mock configurations in git with application code
7. **Monitoring:** Use the test endpoint to validate mock behavior before deployment
