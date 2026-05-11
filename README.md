# Spring Gateway Toolkit

[![CI Build](https://github.com/iFrugal/gateway/actions/workflows/ci.yml/badge.svg)](https://github.com/iFrugal/gateway/actions/workflows/ci.yml)
[![Quality Gate](https://sonarcloud.io/api/project_badges/measure?project=iFrugal_gateway&metric=alert_status)](https://sonarcloud.io/dashboard?id=iFrugal_gateway)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=iFrugal_gateway&metric=coverage)](https://sonarcloud.io/dashboard?id=iFrugal_gateway)
[![Maven Central](https://img.shields.io/maven-central/v/com.github.ifrugal/spring-gateway-toolkit.svg)](https://central.sonatype.com/artifact/com.github.ifrugal/spring-gateway-toolkit)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

A comprehensive Spring Cloud Gateway toolkit providing request/response logging, caching, mock API framework (Conman), and OAuth2 security — all configurable via YAML.

## Maven Coordinates

```xml
<dependency>
    <groupId>com.github.ifrugal</groupId>
    <artifactId>gateway-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Modules

| Module | ArtifactId | Description |
|--------|-----------|-------------|
| Core Library | `gateway-core` | Caching, logging, Conman mock framework, filters |
| Spring Boot Starter | `gateway-starter` | Auto-configuration for all features |
| Standalone App | `gateway-app` | Ready-to-run gateway application |

## Features

### 1. Request/Response Logging
Structured request/response logging with configurable path patterns, body capture, sensitive header redaction, and automatic `x-request-id` propagation. Ignore paths are configurable to skip health checks and documentation endpoints.

### 2. Response Caching
Caffeine-based in-memory caching with per-entry TTL via Caffeine's variable expiration. Cache keys are deterministic (sorted query parameters) to prevent collisions. Includes a REST management API for cache inspection and invalidation.

### 3. Conman — Mock API Framework
YAML-based mock endpoint configuration with multi-tenant support, request validation (JSON Schema, headers, query params), template-based response bodies, and runtime registration via REST API. Upload size limits enforced for security.

### 4. Security & OAuth2
OAuth2 Resource Server (JWT validation) and OAuth2 Login flow with configurable guest/public paths, protected admin endpoints, and seamless Swagger UI integration.

### 5. CORS Configuration
Fully configurable via YAML — allowed origins, methods, headers, and credentials.

### 6. Service Discovery (Optional)
No vendor lock-in — works without service discovery. Profile-based activation for Consul, Eureka, or static routes.

## Quick Start

### Use as a Library

```java
@SpringBootApplication
@EnableGatewayToolkit
public class MyGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyGatewayApplication.class, args);
    }
}
```

Selectively disable features via annotation:

```java
@EnableGatewayToolkit(enableCaching = false, enableConman = false)
```

### Use the Standalone Application

```bash
mvn clean package
java -jar gateway-app/target/gateway-app.jar
```

### Use Docker

```bash
cd docker && docker-compose up -d
```

## Configuration

All features are configured under the `gateway` prefix in `application.yml`:

```yaml
gateway:
  logging:
    enabled: true
    ignore-paths:                    # Override default ignored paths
      - /actuator/health
      - /swagger-ui/**
    requests:
      - paths: ["/api/**"]
        methods: ["*"]
        exclude-body: false

  caching:
    enabled: true
    default-ttl: 86400
    max-size: 10000
    rules:
      - paths: ["/api/products/**"]
        methods: [GET]
        ttl: 3600

  conman:
    enabled: true
    servlet-uri-mappings: [/mock/**]

  security:
    enabled: true
    guest-allowed-paths: [/api/public/**, /mock/**]
    oauth2:
      enabled: true
      provider:
        issuer-uri: https://auth.example.com
```

See [Configuration Reference](docs/configuration-reference.md) for all properties.

## API Endpoints

### Cache Management
- `GET /gateway/cache` — List all cache keys
- `GET /gateway/cache/{key}` — Get cached value
- `POST /gateway/cache/{key}?value=...&ttlSeconds=...` — Set cache value
- `DELETE /gateway/cache/{key}` — Invalidate cache entry
- `DELETE /gateway/cache` — Clear all cache

### Conman Admin
- `GET /conman/admin/mocks` — List all mock configurations
- `POST /conman/admin/register` — Register new mocks (multipart YAML, max 1 MB)
- `POST /conman/admin/reload` — Reload mocks from configured files
- `DELETE /conman/admin/mocks` — Clear all mocks
- `GET /conman/admin/test?httpMethod=GET&uri=/mock/hello` — Test mock lookup

## Documentation

| Document | Description |
|----------|-------------|
| [Architecture Overview](docs/architecture-overview.md) | High-level design, request flow, and design principles |
| [Module Structure](docs/module-structure.md) | Package layout, key classes, and extension points |
| [Caching](docs/caching.md) | Cache providers, key generation, and management API |
| [Logging](docs/logging.md) | Structured logging, body capture, and ignore paths |
| [Conman Mock Framework](docs/conman.md) | Mock configuration, multi-tenancy, and validation |
| [Security](docs/security.md) | OAuth2, JWT, CORS, and access control |
| [Configuration Reference](docs/configuration-reference.md) | Complete property reference with defaults |
| [Deployment Guide](docs/deployment.md) | Building, Docker, production, and SonarCloud |

## Project Structure

```
spring-gateway-toolkit/
├── gateway-core/          # Core library (reusable JAR)
├── gateway-starter/       # Spring Boot Starter (auto-configuration)
├── gateway-app/           # Standalone application
├── docs/                  # Architecture documentation
├── docker/                # Docker configuration
├── .github/workflows/     # CI/CD (build, test, SonarCloud, release)
└── pom.xml                # Parent POM
```

## Building

```bash
# Build all modules with tests and coverage
mvn clean verify

# Build only the library
mvn clean install -pl gateway-core,gateway-starter

# Run SonarCloud analysis locally
mvn sonar:sonar -Dsonar.token=$SONAR_TOKEN
```

## Requirements

- Java 25 LTS or later
- Maven 3.9+
- Docker (optional)

## Quality

CI runs on every push/PR to `master` with JaCoCo code coverage and SonarCloud static analysis. Tests are also executed during Maven Central releases (no `maven.test.skip`).

**Required GitHub Secrets for SonarCloud:** `SONAR_TOKEN`

## Release

Releases are automated via GitHub Actions. Tag a version (`v1.0.0`) or trigger `release-action.yml` manually to publish to Maven Central.

**Required GitHub Secrets:**
- `CENTRAL_USERNAME` — Maven Central (Sonatype) username
- `CENTRAL_TOKEN` — Maven Central token
- `GPG_PRIVATE_KEY` — GPG private key for signing artifacts
- `GPG_PASSPHRASE` — GPG key passphrase

## Security

When deploying in production, enable security (`gateway.security.enabled=true`) to protect admin endpoints (`/gateway/cache/**`, `/conman/admin/**`). See [Security docs](docs/security.md) and [SECURITY.md](SECURITY.md) for details.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for development setup, code style, and pull request guidelines.

## License

[Apache License 2.0](LICENSE)
