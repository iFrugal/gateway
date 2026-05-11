# Logging Subsystem

`LoggingAndCachingWebFilter` is the toolkit's single hot-path filter. It runs at `Ordered.HIGHEST_PRECEDENCE`, intercepts every request that survives the ignore-path list, and emits structured request/response logs. The same filter handles response caching — body capture happens once and is shared between the two concerns.

## Configuration

```yaml
gateway:
  logging:
    enabled: true                # default true
    level: info                  # default "info"
    max-body-bytes: 65536        # default 65536 (64 KiB); set 0 to disable truncation
    sensitive-headers:           # default list — see below
      - Authorization
      - Cookie
      - Set-Cookie
      - Proxy-Authorization
      - X-API-Key
      - X-Auth-Token
    ignore-paths:                # default: /actuator/health, /swagger-ui/**, /v3/api-docs/**, /swagger-resources/**
      - /actuator/health
      - /swagger-ui/**
    requests:                    # per-route logging rules
      - paths: ["/api/users/**"]
        methods: [GET, POST]
        exclude-body: false      # capture & log request/response body
      - paths: ["/api/auth/login"]
        methods: [POST]
        exclude-body: true       # log everything except the body
```

### `LoggingProperties` fields

| Property | Default | Notes |
|---|---|---|
| `gateway.logging.enabled` | `true` | Master switch for the filter. |
| `gateway.logging.level` | `"info"` | Reserved for future use; emitted log lines are currently always at `INFO`. |
| `gateway.logging.max-body-bytes` | `65536` (64 KiB) | Maximum bytes captured into the in-memory copy used for logging and caching. Bodies still flow to upstream / downstream **in full** — only the captured copy is truncated. Set to `0` to disable the cap entirely (legacy behaviour; not recommended). Tune **below** `spring.codec.max-in-memory-size` — the framework limit is the real ceiling and this property is the finer-grained cap. |
| `gateway.logging.sensitive-headers` | `[Authorization, Cookie, Set-Cookie, Proxy-Authorization, X-API-Key, X-Auth-Token]` | Header names whose values are replaced with `[REDACTED]` in structured log output. Matching is case-insensitive. Setting an empty list disables redaction. |
| `gateway.logging.ignore-paths` | health/swagger defaults | Patterns excluded from *both* logging and caching. The filter exits early without wrapping the exchange. |
| `gateway.logging.requests[]` | `[]` | Per-route rules; see `RequestConfig` below. |

### `RequestConfig`

| Property | Notes |
|---|---|
| `paths` | Ant-style patterns. A route is matched if any pattern matches the incoming path. |
| `methods` | List of HTTP methods, or `"*"` for all methods. |
| `exclude-body` | `false` by default. When `true`, the request/response body is **not** captured for this route — useful for `POST /login` or anything that carries credentials in the body. |

## Body capture and the byte cap

`BodyCaptureRequest` and `BodyCaptureResponse` are only wired into the exchange when *either* a logging rule with `exclude-body: false` matches *or* a cache rule matches. Routes outside those conditions skip body capture entirely.

When body capture is active:

- The **request body** is joined into a single `DataBuffer`, decoded to UTF-8, and held in a cached `Mono<String>`. If the decoded byte count exceeds `max-body-bytes`, the cached string contains the first `max-body-bytes` followed by the marker `...[truncated]`.
- The **response body** is accumulated chunk-by-chunk into a synchronised buffer. Once the byte count reaches `max-body-bytes`, subsequent chunks are not retained in the captured copy (they still stream to the client). The captured string ends in `...[truncated]` and `getResponse().isTruncated()` returns `true`.

The cap is per-request, per-response. It is **not** a global limit. With a 64 KiB cap and 1000 concurrent in-flight requests that all hit body capture, the upper bound on body-capture heap is ~64 MiB — predictable and bounded.

### Disabling truncation

Set `gateway.logging.max-body-bytes: 0` if you genuinely need full bodies in logs. Be aware this restores the pre-`1.1.0` unbounded behaviour: a 100 MiB upload will hold a 100 MiB Java `String` for the lifetime of the request. The framework limit (`spring.codec.max-in-memory-size`) still applies above this cap.

## Automatic request ID

Every request gets an `x-request-id` header:

- If the client supplied one, it is preserved verbatim and emitted in all log lines for the request.
- If not, the filter mutates the request to add a fresh `UUID.randomUUID()` before any downstream filter sees it.

The same `x-request-id` is included in both the request and response log entries — use it to join the two.

## Sensitive-header redaction

The filter copies the request headers into a fresh map before logging, then replaces the values for any name in `gateway.logging.sensitive-headers` with the single-element list `["[REDACTED]"]`. The match is case-insensitive: configuring `Authorization` redacts `Authorization`, `authorization`, and `AUTHORIZATION` equally.

**Defaults** redact:

- `Authorization`
- `Cookie`
- `Set-Cookie`
- `Proxy-Authorization`
- `X-API-Key`
- `X-Auth-Token`

**Extending the list** — add your application's secret-carrying headers to the YAML:

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
      - X-Tenant-Secret      # your custom header
      - X-Signature
```

The redacted map is what the filter logs; the original headers reach the upstream service unmodified.

## Body-capture classes

The three classes that implement the capture are public for advanced extension:

| Class | Decorates | Notes |
|---|---|---|
| `BodyCaptureRequest` | `ServerHttpRequest` | Provides `getFullBodyAsync()` (returns `Mono<String>`). The synchronous `getFullBody()` is `@Deprecated(forRemoval = true)` — it returns `""` if called before the body has arrived. Use the async variant. |
| `BodyCaptureResponse` | `ServerHttpResponse` | Accumulates bytes under a `synchronized` lock; safe under any Reactor scheduling pattern. Exposes `getFullBody()` and `isTruncated()`. |
| `BodyCaptureExchange` | `ServerWebExchange` | Pairs the two wrappers above with a shared `maxCaptureBytes`. |

If you need to subclass any of these, prefer constructor injection of the byte cap so your subclass honours the global `gateway.logging.max-body-bytes` setting.

## Default ignore-paths

When `gateway.logging.ignore-paths` is empty (the default), the filter applies a built-in list:

- `/actuator/health`
- `/actuator/health/ping`
- `/swagger-ui/**`
- `/v3/api-docs/**`
- `/swagger-resources/**`

Providing a non-empty `ignore-paths` value **replaces** the built-in list — there is no additive merge. Re-include the defaults explicitly if you want to keep them.

## Structured log shape

Each log line is a Java `Map` serialised by SLF4J's default formatter (Logback's JSON encoder, in `gateway-app`'s default profile, produces machine-parseable output). Fields:

| Field | Notes |
|---|---|
| `timestamp` | ISO-8601 instant captured at log emission. |
| `requestId` | The `x-request-id` for this request. |
| `method`, `path`, `queryParams` | Incoming request. |
| `headers` | Sensitive headers replaced with `[REDACTED]`. |
| `body` | Request or response body, possibly with the `...[truncated]` marker. Omitted entirely when `exclude-body: true` or when the body is empty/blank. |
| `status` | Response status code. |
| `durationMs` | Elapsed time from the filter receiving the request to the chain completing. |

Two log lines are emitted per request: `Request: {…}` before the chain proceeds, `Response: {…}` after.

## Performance notes

- Routes excluded by `ignore-paths` short-circuit at the top of the filter — no decoration, no logging.
- Routes that match a logging rule with `exclude-body: true` and no cache rule still skip body capture entirely; only `headers` + `status` + `durationMs` are logged.
- With `max-body-bytes` at the default 64 KiB, the per-request heap cost of a logged-body request is approximately `64 KiB` (request copy) `+ 64 KiB` (response copy). Set this lower on memory-constrained deployments; set it higher only if you genuinely log full payloads.
- The filter does not buffer logs — emission is synchronous via SLF4J. Configure your logging backend's appenders (async, batched, etc.) at the SLF4J/Logback layer.

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| No log lines at all | `gateway.logging.enabled: false`, or path matches `ignore-paths` | Flip the flag; check the path. |
| `requestId` missing | Filter not registered; check `@AutoConfiguration` import | Verify `gateway-starter` is on the classpath; the filter has `@ConditionalOnMissingBean` so a user-registered override may have replaced it. |
| Bodies empty in logs | `exclude-body: true` for the matching rule, OR request body never arrived | Inspect the matching `RequestConfig`; if you see `...[truncated]` instead of empty, the cap is firing as expected. |
| `...[truncated]` showing up for "small" bodies | `max-body-bytes` is too low for your traffic shape | Increase the property; or accept the truncation. |
| Custom secret header leaking | Header is not in `sensitive-headers` | Add it to the YAML list — see "Extending the list" above. |
