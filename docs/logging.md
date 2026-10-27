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

## Request IDs

`LoggingAndCachingWebFilter` assigns exactly one request id to every request, before any other filter runs.
This includes paths in `ignore-paths` and cache hits; `ignore-paths` only skips logging and caching.

### Validation

The first value of the request-id header is the only candidate.
It is kept only if it fully matches `gateway.request-id.pattern` (default `^[A-Za-z0-9._-]{8,64}$`).
Anything else is replaced with a random `UUID.randomUUID()`: a missing or blank header, a value shorter than 8 or longer than 64 characters, or one containing spaces, CR/LF, commas, quotes, non-ASCII or any other character outside the set.
The rejected value is never logged, because it is untrusted input.
The forwarded request carries exactly one value of the header (it is set, not added), so duplicate inbound values never reach the upstream.

### Echo on every response

The final id is set on the response immediately and again in a `ServerHttpResponse.beforeCommit` callback.
The second write runs just before the headers are flushed, so the gateway's id wins over a request-id header copied from the upstream response.
The id is therefore echoed on proxied responses, cache hits, ignored paths such as `/actuator/health`, 401/403 responses written by Spring Security, and error responses produced by the WebFlux error handler.

### Where the id is available

| Location | Key | Notes |
|---|---|---|
| Request header (forwarded) | `gateway.request-id.header-name` | Exactly one value. |
| Response header (echo) | `gateway.request-id.header-name` | Exactly one value, on every response. |
| Exchange attribute | `LoggingAndCachingWebFilter.REQUEST_ID_ATTRIBUTE` | Readable by any later filter, handler or error attribute. |
| Reactor Context | `LoggingAndCachingWebFilter.REQUEST_ID_CONTEXT_KEY` (`requestId`) | Visible to every operator of every downstream filter. |
| SLF4J MDC | `gateway.request-id.mdc-key` (default `requestId`) | Requires `gateway.request-id.context-propagation: true` (default). |
| WebFlux error body | `requestId` | Replaces Spring Boot's internal request id; see below. |

### MDC propagation

The starter registers a Micrometer context-propagation `ThreadLocalAccessor` (`RequestIdThreadLocalAccessor`) that maps the Reactor Context key `requestId` to the configured MDC key.
It then calls `Hooks.enableAutomaticContextPropagation()`.
With that hook, Reactor sets the MDC entry around the subscription of the filter chain and around every signal and scheduler hop, and removes it afterwards.
As a result, log statements see the id in these places:

- in the body of any later `WebFilter` (which runs at subscription time inside `DefaultWebFilterChain`'s `Mono.defer`);
- after thread hops such as `Mono.delay`, `publishOn` or `subscribeOn`;
- after the proxied upstream call completes on a Netty thread;
- in the toolkit's own `Request:` and `Response:` log lines.

The entry is cleared when the request's processing leaves the Reactor operators, so it does not leak to the next request served on the same Netty event-loop thread.
Only the configured MDC key is touched; other MDC entries are left alone.

Spring Boot has its own switch, `spring.reactor.context-propagation` (`limited` by default, `auto` calls the same Reactor hook).
The hook is idempotent, so both switches can be on without double work or conflict.
Setting `gateway.request-id.context-propagation: false` skips both the accessor registration and the hook; the id is then still in the exchange attribute and the Reactor Context, but not in the MDC.
Automatic propagation is a JVM-wide Reactor setting and stays enabled until the JVM exits.

To print the id in plain-text logs, add `%X{requestId}` to your Logback pattern.
The JSON appender in `gateway-app`'s `logback-spring.xml` already includes the `requestId` MDC key.

### Error bodies

Spring Boot's `DefaultErrorAttributes` puts the internal `ServerHttpRequest` id into the `requestId` field of WebFlux error JSON.
The starter registers `RequestIdErrorAttributes`, which replaces that value with the gateway request id, so the error body, the echoed header and the MDC all agree.
It is registered before Boot's `ErrorWebFluxAutoConfiguration` and backs off if the application defines its own `ErrorAttributes` bean.

### CORS

Because the id is always echoed, the configured request-id header is added to `Access-Control-Expose-Headers` automatically.
It is merged with `gateway.cors.exposed-headers` without case-insensitive duplicates.

The same id is included in both the request and response log entries; use it to join the two.

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
| `requestId` | The final request id for this request (validated inbound value or generated UUID), read from the exchange attribute so a custom `gateway.request-id.header-name` works. |
| `method`, `path`, `queryParams` | Incoming request. |
| `headers` | Sensitive headers replaced with `[REDACTED]`. |
| `body` | Request or response body, possibly with the `...[truncated]` marker. Omitted entirely when `exclude-body: true` or when the body is empty/blank. |
| `status` | Response status code. |
| `durationMs` | Elapsed time from the filter receiving the request to the chain completing. |

Two log lines are emitted per request: `Request: {…}` before the chain proceeds, `Response: {…}` after.

## Performance notes

- Routes excluded by `ignore-paths` skip body decoration, logging and caching; they only get a request id assigned, forwarded and echoed.
- Routes that match a logging rule with `exclude-body: true` and no cache rule still skip body capture entirely; only `headers` + `status` + `durationMs` are logged.
- With `max-body-bytes` at the default 64 KiB, the per-request heap cost of a logged-body request is approximately `64 KiB` (request copy) `+ 64 KiB` (response copy). Set this lower on memory-constrained deployments; set it higher only if you genuinely log full payloads.
- The filter does not buffer logs — emission is synchronous via SLF4J. Configure your logging backend's appenders (async, batched, etc.) at the SLF4J/Logback layer.

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| No log lines at all | `gateway.logging.enabled: false`, or path matches `ignore-paths` | Flip the flag; check the path. |
| `requestId` missing | Filter not registered; check `@AutoConfiguration` import | Verify `gateway-starter` is on the classpath; the filter has `@ConditionalOnMissingBean` so a user-registered override may have replaced it. |
| `requestId` missing from MDC only | `gateway.request-id.context-propagation: false`, or your log pattern lacks `%X{requestId}` | Re-enable the property; add the MDC key to the pattern or encoder. |
| Client-sent id replaced with a UUID | Value does not match `gateway.request-id.pattern` | Send 8 to 64 characters from `A-Z a-z 0-9 . _ -`, or adjust the pattern. |
| Bodies empty in logs | `exclude-body: true` for the matching rule, OR request body never arrived | Inspect the matching `RequestConfig`; if you see `...[truncated]` instead of empty, the cap is firing as expected. |
| `...[truncated]` showing up for "small" bodies | `max-body-bytes` is too low for your traffic shape | Increase the property; or accept the truncation. |
| Custom secret header leaking | Header is not in `sensitive-headers` | Add it to the YAML list — see "Extending the list" above. |
