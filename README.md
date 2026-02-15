# Spring Gateway Toolkit

[![CI Build](https://github.com/iFrugal/spring-gateway-toolkit/actions/workflows/ci.yml/badge.svg)](https://github.com/iFrugal/spring-gateway-toolkit/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/com.github.ifrugal/spring-gateway-toolkit.svg)](https://central.sonatype.com/artifact/com.github.ifrugal/spring-gateway-toolkit)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

A comprehensive Spring Cloud Gateway toolkit providing request/response logging, caching, mock API framework (Conman), and OAuth2 security - all configurable via YAML.

## Maven Coordinates

```xml
<!-- Parent coordinates -->
<groupId>com.github.ifrugal</groupId>
<artifactId>spring-gateway-toolkit</artifactId>
```

### Modules

| Module | ArtifactId | Description |
|--------|-----------|-------------|
| Core Library | `gateway-core` | Caching, logging, Conman mock framework, filters |
| Spring Boot Starter | `gateway-starter` | Auto-configuration for all features |
| Standalone App | `gateway-app` | Ready-to-run gateway application |

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

### 6. Service Discovery (Optional)
- **No vendor lock-in** - works without any service discovery
- Profile-based activation for Consul, Eureka, or static routes
- Easy to extend for Kubernetes, Zookeeper, etc.

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
├── .github/workflows/     # CI/CD
│   ├── ci.yml             # Build + test on PRs
│   └── release-action.yml # Maven Central release
└── pom.xml                # Parent POM
```

## Quick Start

### Option 1: Use as a Library

Add the starter dependency to your project:

```xml
<dependency>
    <groupId>com.github.ifrugal</groupId>
    <artifactId>gateway-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

Enable in your application:

```java
@SpringBootApplication
@EnableGatewayToolkit
public class MyGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyGatewayApplication.class, args);
    }
}
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
cd docker
docker-compose up -d
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

## Requirements

- Java 21+
- Maven 3.8+
- Docker (optional)

## Release

Releases are automated via GitHub Actions. On push to `master`, the `release-action.yml` workflow runs `mvn release:prepare release:perform` to publish to Maven Central.

**Required GitHub Secrets:**
- `CENTRAL_USERNAME` — Maven Central (Sonatype) username
- `CENTRAL_TOKEN` — Maven Central token
- `GPG_PRIVATE_KEY` — GPG private key for signing artifacts
- `GPG_PASSPHRASE` — GPG key passphrase

## License

[Apache License 2.0](LICENSE)
