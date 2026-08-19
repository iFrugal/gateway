# Conman Mocking Guide — for developers

A cookbook of everything you can do with Conman mocks, from a static stub to
input-driven templated responses. Every example here has been verified against
the engine. For configuration reference (properties, admin API details,
security), see [conman.md](conman.md).

Conman serves mock endpoints from inside the gateway itself. You describe each
mock in YAML: what request it matches, optional validation of the incoming
request, and the response to return. Mocks load from files at startup and can
be pushed at runtime over the admin API.

## 0. Enable it

```yaml
gateway:
  conman:
    enabled: true                      # default is false
    servlet-uri-mappings: ["/mock/**"] # paths Conman owns
    mapping-files:
      - classpath:conman.yml           # top-level YAML *list* of mocks
```

A mapping file is a top-level YAML list. Each entry is one mock.

## 1. Static mock — the simplest thing that works

```yaml
- request:
    uri: /mock/health
    httpMethod: GET
  response:
    statusCode: 200
    body: |
      {"status": "UP", "service": "payments-stub"}
```

```bash
curl -s http://localhost:8080/mock/health
# {"status": "UP", "service": "payments-stub"}
```

**How matching works — read this first:**

- The lookup key is exactly `(httpMethod, path, tenant)`. The path must match
  **character for character**. There are **no path variables and no
  wildcards**: `uri: /mock/users/{id}` matches only the literal path
  `/mock/users/{id}`, never `/mock/users/42`. Register one mock per concrete
  path you need.
- The query string is **not** part of the match. `/mock/users?page=2` hits the
  `/mock/users` mock (and the template can read `page` — see below).
- No match returns `404` with a JSON body naming the method, URI and tenant it
  looked for.

## 2. Status codes, response headers, error simulation

Every field of the response is yours to set. This makes Conman a handy fault
injector:

```yaml
# Simulate an upstream failure
- request:
    uri: /mock/payments/charge
    httpMethod: POST
  response:
    statusCode: 502
    body: |
      {"error": "upstream_unavailable", "detail": "simulated outage"}

# Simulate rate limiting, with headers
- request:
    uri: /mock/payments/quote
    httpMethod: GET
  response:
    statusCode: 429
    responseHeaders:
      Retry-After: "30"
      X-RateLimit-Remaining: "0"
    body: |
      {"error": "rate_limited"}
```

Two things to know:

- **`Content-Type` is always `application/json`.** The handler sets it
  unconditionally; the `response.contentType` field exists in the model but is
  not applied. Don't fight it — Conman mocks JSON APIs.
- **`responseHeaders` values are literal.** They do NOT pass through the
  template engine. `X-Request-Id: "${uuid1}"` returns the literal text
  `${uuid1}`. Only the body is templated.

## 3. `bodyObj` — structured body instead of a string

If you prefer real YAML structure over an embedded JSON string, use `bodyObj`.
It is serialized to (pretty-printed) JSON:

```yaml
- request:
    uri: /mock/catalog/item
    httpMethod: GET
  response:
    statusCode: 200
    bodyObj:
      id: 42
      name: Widget
      priceCents: 1999
      tags: [new, featured]
```

`bodyObj` wins if both `bodyObj` and `body` are set. Template placeholders
inside `bodyObj` values also work when `bodyTemplate: true` (the object is
serialized first, then templated).

## 4. Templated responses — `bodyTemplate: true`

Set `bodyTemplate: true` and the body runs through a FreeMarker template
engine per request. **Without that flag, `${...}` is returned as literal
text** — the number one "my template doesn't work" mistake.

What's available inside a template:

| Expression | Value | Notes |
|---|---|---|
| `${request.requestUri}` | request path | |
| `${request.httpMethod}` | `GET`, `POST`, ... | |
| `${request.headers['x-request-id']}` | request header | single-valued map; use bracket syntax for dashed names |
| `${request.params.page[0]}` | query parameter | values are **lists** — the `[0]` index is mandatory |
| `${request.body.name}` | JSON body field | **only when the mock declares `validation.bodySchema`** — see section 5 |
| `${uuid1}` | fresh random UUID per request | |
| `${.now?string('yyyy-MM-dd HH:mm:ss')}` | current timestamp | any `SimpleDateFormat` pattern |
| `${uuid()}` | another random UUID (function form) | |
| `${trim(' x ')}` | trimmed string | |

Simple example:

```yaml
- request:
    uri: /mock/whoami
    httpMethod: GET
  response:
    statusCode: 200
    bodyTemplate: true
    body: |
      {
        "method": "${request.httpMethod}",
        "uri": "${request.requestUri}",
        "caller": "${(request.headers['x-caller'])!'anonymous'}",
        "page": "${(request.params.page[0])!'1'}",
        "traceId": "${uuid1}",
        "servedAt": "${.now?string('yyyy-MM-dd HH:mm:ss')}"
      }
```

```bash
curl -s 'http://localhost:8080/mock/whoami?page=3' -H 'x-caller: checkout-service'
# {"method":"GET", "uri":"/mock/whoami", "caller":"checkout-service", "page":"3", ...}
```

**Missing values throw.** `${request.params.missing[0]}` on a request without
that parameter is a template error, and a template error fails the whole
response. Always guard optional inputs with the parenthesized default
operator: `${(request.params.page[0])!'1'}`. The parentheses matter — the
unparenthesized form does not protect the full expression.

## 5. Input-driven responses — reading the request body

To use `${request.body...}`, the mock **must declare a body schema**. Body
capture happens during validation; without `validation.bodySchema` the body is
never read and `request.body` does not exist in the template context. A
permissive schema is enough:

```yaml
- request:
    uri: /mock/orders
    httpMethod: POST
    validation:
      bodySchema: |
        {"$schema": "https://json-schema.org/draft/2020-12/schema", "type": "object"}
  response:
    statusCode: 201
    bodyTemplate: true
    body: |
      {
        "orderId": "${uuid1}",
        "customer": "${(request.body.customerId)!'unknown'}",
        "city": "${(request.body.address.city)!'-'}",
        "status": "CREATED"
      }
```

```bash
curl -s -X POST http://localhost:8080/mock/orders \
  -H 'Content-Type: application/json' \
  -d '{"customerId": "c-1", "address": {"city": "Pune"}}'
# {"orderId":"<uuid>", "customer":"c-1", "city":"Pune", "status":"CREATED"}
```

Nested access (`request.body.address.city`) and numbers work directly. Two
gotchas:

- **You cannot echo the whole body.** `${request.body}` is a map and fails to
  render. Echo the fields you care about, or use the iteration recipe below.
- Strings containing quotes will break your JSON — escape them with
  `${request.body.note?json_string}`.

## 6. Conditional and repeated content — full FreeMarker

The body is a real FreeMarker template, so directives work:

**Branch on input:**

```yaml
- request:
    uri: /mock/loans/decision
    httpMethod: POST
    validation:
      bodySchema: |
        {"$schema": "https://json-schema.org/draft/2020-12/schema",
         "type": "object", "required": ["amount"],
         "properties": {"amount": {"type": "number"}}}
  response:
    statusCode: 200
    bodyTemplate: true
    body: |
      {
        "decision": "<#if request.body.amount gt 50000>REFERRED<#else>APPROVED</#if>",
        "amount": ${request.body.amount?c}
      }
```

`?c` renders a number in plain computer format — use it whenever you emit a
number *as JSON* rather than inside a string.

**Iterate a list from the request:**

```yaml
      # body: {"items": [{"sku": "a1"}, {"sku": "b2"}]}
    body: |
      {
        "lines": [
          <#list request.body.items as item>
          {"sku": "${item.sku}", "status": "RESERVED"}<#sep>,</#sep>
          </#list>
        ]
      }
```

**Scalar-echo recipe** (closest thing to echoing the body — copies string and
number fields, skips nested objects/arrays). Filter first, then list; filtering
inside the loop with `<#if>` leaves trailing separators and breaks the JSON:

```yaml
    body: |
      {<#list request.body?keys?filter(k -> request.body[k]?is_string || request.body[k]?is_number) as k>"${k}": "${request.body[k]}"<#sep>, </#list>}
```

**Existence checks:** `<#if (request.body.email)??>...has email...<#else>...no email...</#if>`

Useful builtins verified to work: `?upper_case`, `?lower_case`,
`?json_string`, `?string('<date pattern>')`, `?c`, `?keys`, `?is_string`,
`?is_number`.

## 7. Request validation — reject bad input with a 400

Validation runs before the response is built. On failure the caller gets
`400 Bad Request` with the failure message.

```yaml
- request:
    uri: /mock/users
    httpMethod: POST
    validation:
      headers:
        authorization:               # header names: use lowercase
          required: true
          regexValidator: "Bearer .*"
      queryParams:
        source:
          required: true
      bodySchema: |
        {
          "$schema": "https://json-schema.org/draft/2020-12/schema",
          "type": "object",
          "required": ["name", "email"],
          "properties": {
            "name":  {"type": "string", "minLength": 1},
            "email": {"type": "string", "format": "email"},
            "age":   {"type": "integer", "minimum": 0}
          }
        }
  response:
    statusCode: 201
    bodyTemplate: true
    body: |
      {"id": "${uuid1}", "name": "${request.body.name}"}
```

Header/query rules support per-field:

| Field | Meaning |
|---|---|
| `required: true` | field must be present |
| `regexValidator: "<regex>"` | value must match the regex |
| `typeFqcn` | value must be of this Java type (rarely needed for HTTP strings) |

`bodySchema` is full JSON Schema (networknt validator). Declare the dialect
with `$schema` (2020-12 is assumed when absent). A large schema can live in
its own file: `bodySchemaFile: /app/schemas/create-user.json` (filesystem
path, loaded lazily). A malformed schema fails at **load time**, not on the
first request.

Body validation implies the body is required: an empty body, `{}`, `[]`,
`null`, or `Content-Length: 0` is rejected before the schema even runs.

## 8. Multi-tenant mocks — different responses per caller

Conman resolves a tenant from a request header (default `tenant-id`,
configurable via `gateway.conman.tenant-id-header`). A mock can be pinned to
one tenant, several, or none (the default/fallback mock):

```yaml
# Tenant-specific response
- tenantId: acme
  request:
    uri: /mock/plan
    httpMethod: GET
  response:
    statusCode: 200
    body: |
      {"plan": "enterprise"}

# Same mock for several tenants
- tenantIds: [globex, initech]
  request:
    uri: /mock/plan
    httpMethod: GET
  response:
    statusCode: 200
    body: |
      {"plan": "team"}

# Fallback for everyone else (no tenantId field)
- request:
    uri: /mock/plan
    httpMethod: GET
  response:
    statusCode: 200
    body: |
      {"plan": "free"}
```

```bash
curl -s http://localhost:8080/mock/plan -H 'tenant-id: acme'   # enterprise
curl -s http://localhost:8080/mock/plan -H 'tenant-id: other'  # free (fallback)
curl -s http://localhost:8080/mock/plan                        # free (fallback)
```

Lookup order: exact `(method, uri, tenant)` first, then `(method, uri, null)`.
A tenant with no tenant-specific mock and no fallback gets a 404.

## 9. Managing mocks at runtime — the admin API

Everything under `/conman/admin/**`. **Secure these endpoints** (they are open
unless `gateway.security.enabled=true`).

```bash
# Push a YAML file of mocks into the running gateway (max 1 MB)
curl -s -X POST http://localhost:8080/conman/admin/register \
  -F 'registrationFile=@my-mocks.yml'

# Same, pinned to a tenant (overrides any tenantId inside the file)
curl -s -X POST http://localhost:8080/conman/admin/register \
  -F 'registrationFile=@my-mocks.yml' -F 'tenantId=acme'

# What is registered right now?
curl -s http://localhost:8080/conman/admin/mocks

# Which mock would this request hit? (lookup only, does not execute)
curl -s 'http://localhost:8080/conman/admin/test?httpMethod=GET&uri=/mock/plan&tenantId=acme'

# Reset to the startup files (clears runtime registrations)
curl -s -X POST http://localhost:8080/conman/admin/reload

# Clear everything (files are NOT re-read; use /reload for that)
curl -s -X DELETE http://localhost:8080/conman/admin/mocks
```

Registering the same `(method, uri, tenant)` again **replaces** the previous
mock — that is how you update a mock in place during a test session.

## 10. Gotchas checklist

Things that bite people, all verified against the engine:

1. `${...}` in the body but no `bodyTemplate: true` → placeholders returned
   as literal text.
2. `${request.body...}` without `validation.bodySchema` → template error
   (body is never captured without validation).
3. `${request.params.x}` without `[0]` → template error (params are lists).
4. Optional input without the parenthesized default
   `${(expr)!'fallback'}` → template error when absent. A template error
   fails the request.
5. `${request.body}` (whole map) → template error. Echo specific fields or
   use the scalar-echo recipe.
6. `responseHeaders` are not templated — `${uuid1}` there stays literal.
7. `response.contentType` is ignored; responses are always
   `application/json`.
8. No path variables or wildcards in `request.uri` — exact match only.
9. Numbers emitted bare into JSON: use `${n?c}`, or FreeMarker may apply
   locale formatting in some configurations.
10. There is no artificial-latency / delay feature — if you need slow
    responses, put a delaying proxy in front or extend the handler.

## 11. Recommended layout

```
src/main/resources/
  conman.yml                 # or split by area:
  conman/users-mocks.yml
  conman/payments-mocks.yml
```

```yaml
gateway:
  conman:
    enabled: true
    mapping-files:
      - classpath:conman/users-mocks.yml
      - classpath:conman/payments-mocks.yml
```

Keep files small and focused; `POST /conman/admin/reload` re-reads all of
them. Treat mock files as trusted input (they are code: templates execute).
