# Spring Gateway Toolkit

A comprehensive Spring Cloud Gateway toolkit providing request/response logging, caching, mock API framework (Conman), and OAuth2 security - all configurable via YAML.

## Features

### 1. Request/Response Logging
- Log requests and responses based on path and method patterns
- Configurable body capture with sensitive data exclusion
- Structured logging with timestamps, request IDs, and timing

### 2. Response Caching
- Caffeine-based in-memory caching
- Configurable TTL per path pattern
- Wildcard path matching support
- Cache management API

### 3. Conman - Mock API Framework
- YAML-based mock endpoint configuration
- Multi-tenant support
- Request validation (JSON Schema, headers, query params)
- Template-based response bodies
- Runtime registration via REST API

### 4. Security & OAuth2
- OAuth2 Resource Server (JWT validation)
- OAuth2 Login flow
- Configurable guest/public paths
- Seamless Swagger UI integration

### 5. CORS Configuration
- Fully configurable via YAML
- Allowed origins, methods, headers

## Project Structure

```
spring-gateway-toolkit/
├── gateway-core/          # Core library (reusable JAR)
├── gateway-starter/       # Spring Boot Starter (auto-configuration)
├── gateway-app/           # Standalone application
├── docker/                # Docker configuration
│   ├── Dockerfile
│   ├── docker-compose.yml
│   └── config/            # External configuration
└── pom.xml                # Parent POM
```

## Quick Start

### Option 1: Use as a Library

Add the starter dependency to your project:

```xml
<dependency>
    <groupId>io.github.springgateway</groupId>
    <artifactId>gateway-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Option 2: Use the Standalone Application

```bash
# Build
mvn clean package

# Run
java -jar gateway-app/target/gateway-app.jar
```

### Option 3: Use Docker

```bash
# Build and run
cd docker
docker-compose up -d

# With custom configuration
docker-compose up -d gateway
```

## Configuration

All features are configured under the `gateway` prefix in `application.yml`:

```yaml
gateway:
  # Request/Response Logging
  logging:
    enabled: true
    requests:
      - paths: ["/api/**"]
        methods: ["*"]
        exclude-body: false
      - paths: ["/auth/login"]
        methods: [POST]
        exclude-body: true  # Don't log passwords

  # Response Caching
  caching:
    enabled: true
    default-ttl: 86400  # 1 day
    max-size: 10000
    rules:
      - paths: ["/api/products", "/api/categories"]
        methods: [GET]
        ttl: 3600  # 1 hour

  # CORS
  cors:
    enabled: true
    allowed-origins:
      - http://localhost:3000
      - https://myapp.com
    allowed-methods: [GET, POST, PUT, DELETE, OPTIONS]
    max-age: 3600
    allow-credentials: true

  # Conman Mock API
  conman:
    enabled: true
    servlet-uri-mappings:
      - /mock/**
    mapping-files:
      - classpath:conman.yml
      - file:/app/mocks/custom-mocks.yml

  # Security
  security:
    enabled: true
    guest-allowed-paths:
      - /api/public/**
      - /mock/**
    oauth2:
      enabled: true
      provider:
        issuer-uri: https://auth.example.com
        authorization-uri: https://auth.example.com/oauth2/authorize
        token-uri: https://auth.example.com/oauth2/token
      client:
        id: ${OAUTH2_CLIENT_ID}
        secret: ${OAUTH2_CLIENT_SECRET}
        scopes: openid,profile,email
```

## Environment Variables

All configuration can be overridden via environment variables:

| Variable | Description | Default |
|----------|-------------|---------|
| `SERVER_PORT` | Server port | 8080 |
| `GATEWAY_LOGGING_ENABLED` | Enable request logging | true |
| `GATEWAY_CACHING_ENABLED` | Enable response caching | false |
| `GATEWAY_CACHE_DEFAULT_TTL` | Default cache TTL (seconds) | 86400 |
| `GATEWAY_CORS_ENABLED` | Enable CORS | true |
| `GATEWAY_CONMAN_ENABLED` | Enable mock API framework | false |
| `GATEWAY_SECURITY_ENABLED` | Enable security | false |
| `OAUTH2_CLIENT_ID` | OAuth2 client ID | - |
| `OAUTH2_CLIENT_SECRET` | OAuth2 client secret | - |
| `OAUTH2_ISSUER_URI` | OAuth2 issuer URI | - |
| `CONSUL_ENABLED` | Enable Consul discovery | false |
| `CONSUL_HOST` | Consul host | localhost |

## Conman Mock Configuration

Define mocks in YAML:

```yaml
# Simple GET endpoint
- request:
    uri: /mock/users
    httpMethod: GET
  response:
    body: |
      [{"id": 1, "name": "John"}, {"id": 2, "name": "Jane"}]
    contentType: application/json
    statusCode: 200

# POST with validation
- request:
    uri: /mock/users
    httpMethod: POST
    validation:
      bodySchema: |
        {
          "type": "object",
          "properties": {
            "name": {"type": "string", "minLength": 1},
            "email": {"type": "string", "format": "email"}
          },
          "required": ["name", "email"]
        }
      headers:
        authorization:
          required: true
          regexValidator: "Bearer .*"
  response:
    bodyTemplate: true
    body: |
      {
        "id": "${uuid1}",
        "name": "${request.body.name}",
        "created": "${.now?string('yyyy-MM-dd')}"
      }
    statusCode: 201

# Multi-tenant mock
- request:
    uri: /mock/data
    httpMethod: GET
  tenantIds: ["tenant-a", "tenant-b"]
  response:
    body: '{"tenant": "specific data"}'
    statusCode: 200
```

## API Endpoints

### Cache Management
- `GET /gateway/cache` - List all cache keys
- `GET /gateway/cache/{key}` - Get cached value
- `POST /gateway/cache/{key}?value=...&ttlSeconds=...` - Set cache value
- `DELETE /gateway/cache/{key}` - Invalidate cache entry
- `DELETE /gateway/cache` - Clear all cache

### Conman Admin
- `GET /conman/admin/mocks` - List all mock configurations
- `POST /conman/admin/register` - Register new mocks (multipart)
- `POST /conman/admin/reload` - Reload mocks from files
- `DELETE /conman/admin/mocks` - Clear all mocks
- `GET /conman/admin/test?httpMethod=GET&uri=/mock/test` - Test mock lookup

## Building

```bash
# Build all modules
mvn clean install

# Build only the library (for use in other projects)
mvn clean install -pl gateway-core,gateway-starter

# Build Docker image
cd docker
docker build -t spring-gateway-toolkit:latest ..
```

## Docker Usage

### Simple Deployment

```bash
docker run -d \
  -p 8080:8080 \
  -e GATEWAY_CACHING_ENABLED=true \
  -e GATEWAY_CONMAN_ENABLED=true \
  -v ./config:/app/config \
  -v ./mocks:/app/mocks \
  spring-gateway-toolkit:latest
```

### With Docker Compose

```bash
# Start gateway only
docker-compose up -d gateway

# Start with Consul for service discovery
docker-compose --profile consul up -d

# Override configuration
GATEWAY_PORT=9090 docker-compose up -d
```

## Using as a Library

1. Add the dependency
2. Enable features in your `application.yml`
3. Optionally use the `@EnableGatewayToolkit` annotation

```java
@SpringBootApplication
@EnableGatewayToolkit
public class MyGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyGatewayApplication.class, args);
    }
}
```

## Requirements

- Java 21+
- Maven 3.8+
- Docker (optional)

## License

MIT License
