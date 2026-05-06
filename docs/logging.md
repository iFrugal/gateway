# Logging Subsystem

The Spring Gateway Toolkit provides a comprehensive request/response logging subsystem designed to capture and track HTTP traffic through the gateway with minimal performance impact. This system integrates structured logging with request caching capabilities through a unified filter.

## Overview

The logging subsystem is built around the `LoggingAndCachingWebFilter`, which operates at the highest precedence level to ensure all requests and responses pass through a consistent logging pipeline. This unified approach enables request body capture for both logging and caching operations.

## Configuration

Logging is configured under the `gateway.logging.*` namespace in your application properties:

```yaml
gateway:
  logging:
    enabled: true                    # Enable/disable the logging subsystem
    level: DEBUG                     # Log level (DEBUG, INFO, WARN, ERROR)
    ignore-paths:                    # Paths to exclude from logging (Ant-style patterns)
      - /actuator/health
      - /swagger-ui/**
      - /v3/api-docs/**
    requests:                        # Request-specific logging configuration
      - paths: /api/users/**
        methods: GET,POST            # Comma-separated or "*" for all methods
        exclude-body: false          # Capture request body
      - paths: /api/auth/login
        methods: POST
        exclude-body: true           # Skip body for sensitive endpoints
```

### LoggingProperties

The `LoggingProperties` class manages configuration:
- **enabled**: Toggle the entire logging subsystem (default: `true`)
- **level**: Logging level for the logger (default: `INFO`)
- **ignorePaths**: List of Ant-style patterns for endpoints to skip (default: health, swagger, docs endpoints)
- **requests**: List of `RequestConfig` entries defining per-endpoint logging behavior

### RequestConfig

Each entry in the `requests` list defines logging behavior for specific endpoints:
- **paths**: Ant-style pattern(s) matching endpoint paths
- **methods**: Comma-separated HTTP methods or `"*"` to match all methods
- **excludeBody**: Boolean flag to skip body capture for this endpoint (useful for sensitive data)

## Features

### Automatic Request ID

Every request receives a unique identifier via the `x-request-id` header:
- If not present, a new UUID is automatically generated
- If present, the existing value is preserved
- The ID is propagated throughout the request/response lifecycle for traceability

### Structured Logging

All logging output follows a consistent structure containing:

```
timestamp        : ISO-8601 formatted request timestamp
requestId        : Unique request identifier (UUID or existing header value)
method           : HTTP method (GET, POST, PUT, DELETE, etc.)
path             : Request URI path
queryParams      : Query string parameters (if present)
headers          : Request headers with sensitive data removed
body             : Request body (unless excluded via config)
status           : HTTP response status code
durationMs       : Total request/response duration in milliseconds
```

**Note**: Sensitive headers (Authorization, Cookie, X-API-Key, etc.) are automatically redacted from logs to prevent credential exposure.

## Body Capture Classes

The subsystem provides specialized classes for reactive body capture:

- **BodyCaptureRequest**: Wraps ServerHttpRequest to capture and buffer the request body without consuming the stream
- **BodyCaptureResponse**: Wraps ServerHttpResponse to capture the response body
- **BodyCaptureExchange**: Combines both request and response body capture into a single exchange wrapper

These classes enable non-destructive body inspection—the body is captured for logging/caching while remaining fully available for downstream processing.

## Ignore Paths

By default, the following paths are excluded from logging to reduce noise:

- `/actuator/health` — Kubernetes/infrastructure health checks
- `/swagger-ui/**` — Swagger UI static resources
- `/v3/api-docs/**` — OpenAPI specification endpoints
- `/metrics/**` — Prometheus/actuator metrics

Override these defaults via the `gateway.logging.ignore-paths` property to customize which endpoints are logged.

## Integration with Caching

The `LoggingAndCachingWebFilter` operates as a single unified filter handling both logging and caching:
- Request/response bodies are captured once and reused by both subsystems
- Eliminates duplicate body buffering and improves performance
- Operates at `HIGHEST_PRECEDENCE` to intercept all traffic

## Performance Considerations

- Logging has minimal overhead for excluded paths (early termination)
- Request body capture only occurs for configured endpoints
- Structured logging uses async appenders to avoid blocking request processing
- Consider excluding high-traffic, low-value endpoints from detailed logging

## Example Output

```
timestamp=2026-02-25T14:32:15.123Z
requestId=550e8400-e29b-41d4-a716-446655440000
method=POST
path=/api/users
queryParams=
headers={Accept=application/json, Content-Type=application/json}
body={"name":"John Doe","email":"john@example.com"}
status=201
durationMs=45
```

## Troubleshooting

- **Missing request IDs**: Ensure `LoggingAndCachingWebFilter` is properly registered as a bean
- **Body data not captured**: Verify the endpoint path matches a `RequestConfig` pattern and `exclude-body: false`
- **Sensitive data in logs**: Check that custom headers are added to the redaction list if needed
- **Performance degradation**: Review `ignore-paths` configuration to exclude high-frequency endpoints
