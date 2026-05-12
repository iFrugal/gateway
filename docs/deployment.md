# Spring Gateway Toolkit - Deployment Guide

This guide covers all deployment options for the Spring Gateway Toolkit, from development builds to production environments.

## Building from Source

### Prerequisites
- Java 25 LTS or later
- Maven 3.9+
- Git (for cloning the repository)

### Build Command
```bash
mvn clean install
```

This command:
- Compiles all modules: gateway-core, gateway-starter, and gateway-app
- Runs unit tests with JaCoCo code coverage analysis
- Packages modules as JAR files
- Generates Javadoc documentation

**Build Output Locations:**
- `gateway-core/target/gateway-core-1.0.0-SNAPSHOT.jar` - Core library
- `gateway-starter/target/gateway-starter-1.0.0-SNAPSHOT.jar` - Spring Boot starter
- `gateway-app/target/gateway-app-1.0.0-SNAPSHOT.jar` - Standalone application

## Using as a Library (Spring Boot Starter)

To use the Spring Gateway Toolkit in your Spring Cloud Gateway application:

### Step 1: Add Dependency
Add to your `pom.xml`:
```xml
<dependency>
    <groupId>com.github.ifrugal</groupId>
    <artifactId>gateway-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### Step 2: Add a Standard Spring Boot Application Class
No enabling annotation is required — the starter auto-configures itself from the classpath:
```java
@SpringBootApplication
public class MyGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyGatewayApplication.class, args);
    }
}
```

> `1.0.x` shipped an `@EnableGatewayToolkit` annotation. It was removed in `1.1.0` because the starter already loads auto-configuration unconditionally and the annotation only pre-seeded property defaults that YAML / env vars would override anyway. Configure features via `application.yml` instead — see the next section.

### Step 3: Configure Features via application.yml
Each feature is gated by `gateway.<feature>.enabled`. Defaults: logging and CORS are on; caching, conman and security are off until you flip the flag.

### Configuration File (application.yml)
Configure features via YAML properties under the `gateway` prefix:
```yaml
gateway:
  logging:
    enabled: true
    ignore-paths:
      - /actuator/**
      - /swagger-ui/**
  caching:
    enabled: true
    maxSize: 1000
    defaultTtl: 300
  cors:
    enabled: true
    allowed-origins: http://localhost:3000,https://example.com
    allowed-methods: GET,POST,PUT,DELETE,OPTIONS
  conman:
    enabled: true
    servlet-uri-mappings:
      - /api/mock/**
  security:
    enabled: false
```

## Standalone Application

The `gateway-app` module is a fully-configured Spring Boot Gateway application.

### Running the Standalone JAR
```bash
# Build the entire project
mvn clean install

# Run the gateway-app JAR
java -jar gateway-app/target/gateway-app.jar
```

**Environment Variables:**
```bash
SERVER_PORT=8080
SPRING_PROFILES_ACTIVE=default
JAVA_OPTS="-Xms256m -Xmx512m"
```

**Default Profiles Available:**
- `default` - Basic configuration with local services
- `eureka` - Service discovery via Eureka
- `consul` - Service discovery via Consul
- `docker` - Optimized for Docker environments
- `static` - Static route configuration

Activate profiles:
```bash
java -jar gateway-app/target/gateway-app.jar --spring.profiles.active=eureka
```

## Docker Deployment

### Building Docker Image

The project includes a multi-stage Dockerfile optimized for production:

```bash
# Build the Docker image
docker build -t spring-gateway-toolkit:latest -f docker/Dockerfile .

# Run a container
docker run -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=docker \
  -v /path/to/config:/app/config \
  spring-gateway-toolkit:latest
```

### Docker Compose

Use the provided docker-compose.yml for a complete local gateway stack:

```bash
cd docker
docker-compose up -d
```

This starts:
- Spring Gateway application on port 8080
- Mock API services (Conman)
- Swagger UI on port 8080/swagger-ui.html

### Docker Image Details
- **Base Image:** eclipse-temurin:25-jre-alpine (lightweight Java 25 LTS runtime)
- **Non-root User:** runs as `gateway` user (UID 1001) for security
- **Health Check:** configured to verify application health every 30 seconds
- **Volumes:**
  - `/app/config` - External configuration directory
  - `/app/mocks` - Mock API data directory
- **Default Port:** 8080
- **Memory:** 256MB heap min, 512MB heap max (adjustable via JAVA_OPTS)

### Environment Variables
```bash
SERVER_PORT=8080                    # Port the gateway listens on
JAVA_OPTS="-Xms256m -Xmx512m"      # JVM memory settings
SPRING_PROFILES_ACTIVE=docker       # Active Spring profile
```

## Production Recommendations

### 1. Enable Security
Always enable Spring Security for production deployments:
```yaml
gateway:
  security:
    enabled: true
    oauth2:
      enabled: true
      provider:
        authorization-uri: https://your-oauth-provider/authorize
        token-uri: https://your-oauth-provider/token
      client:
        client-id: your-client-id
        client-secret: your-client-secret
        scopes: openid,profile,email
    guest-allowed-paths:
      - /health
      - /info
      - /public/**
```

### 2. Configure CORS Properly
Restrict CORS to trusted origins only:
```yaml
gateway:
  cors:
    enabled: true
    allowed-origins:
      - https://app.example.com
      - https://admin.example.com
    allowed-methods: GET,POST,PUT,DELETE,OPTIONS
    allow-credentials: true
    max-age: 3600
```

### 3. Tune Cache Sizes
Adjust cache settings based on expected request volume:
```yaml
gateway:
  caching:
    enabled: true
    maxSize: 5000              # Number of entries
    defaultTtl: 600            # 10 minutes
```

### 4. Enable Structured Logging
Configure logging for production monitoring:
```yaml
gateway:
  logging:
    enabled: true
    level: INFO                # Avoid DEBUG in production
    ignore-paths:
      - /actuator/**
      - /health
logging:
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss} - %msg%n"
```

### 5. Resource Management
Set appropriate JVM memory for your workload:
```bash
JAVA_OPTS="-Xms1g -Xmx2g -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
```

### 6. Health Checks
Enable Spring Boot Actuator for monitoring:
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      show-details: when-authorized
```

## SonarCloud Integration

The project is configured with SonarCloud for continuous code quality analysis.

### Configuration
- **File:** `sonar-project.properties`
- **Organization:** ifrugal
- **Project Key:** iFrugal_gateway
- **SonarCloud URL:** https://sonarcloud.io

### CI/CD Integration
The GitHub Actions CI pipeline automatically:
1. Builds the project with Maven
2. Runs JaCoCo code coverage analysis
3. Uploads coverage reports to SonarCloud
4. Performs static code analysis

**JaCoCo Coverage:**
- XML reports generated at: `gateway-core/target/site/jacoco/jacoco.xml`
- Coverage thresholds configured in parent POM
- Reports uploaded by CI/CD pipeline

### Manual Analysis
To run SonarCloud analysis locally:
```bash
mvn clean verify
mvn sonar:sonar \
  -Dsonar.projectKey=iFrugal_gateway \
  -Dsonar.organization=ifrugal \
  -Dsonar.host.url=https://sonarcloud.io \
  -Dsonar.login=YOUR_SONARCLOUD_TOKEN
```

## Troubleshooting

### High Memory Usage
Check cache configuration; reduce `maxSize` or lower `defaultTtl`.

### Security 403 Errors
Verify endpoint permissions in `SecurityProperties` and `guest-allowed-paths` configuration.

### Service Discovery Not Working
Ensure the correct profile is active (`eureka` or `consul`) and services are registered.

### Container Exits Immediately
Check Docker logs: `docker logs <container-id>` for startup errors.
