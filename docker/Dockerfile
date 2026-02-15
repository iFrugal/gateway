# =============================================================================
# Spring Gateway Toolkit - Dockerfile
# =============================================================================
# Multi-stage build for optimized Docker image
# =============================================================================

# Stage 1: Build
FROM maven:3.9-eclipse-temurin-21-alpine AS builder

WORKDIR /build

# Copy pom files first for dependency caching
COPY pom.xml .
COPY gateway-core/pom.xml gateway-core/
COPY gateway-starter/pom.xml gateway-starter/
COPY gateway-app/pom.xml gateway-app/

# Download dependencies (cached layer)
RUN mvn dependency:go-offline -B

# Copy source code
COPY gateway-core/src gateway-core/src
COPY gateway-starter/src gateway-starter/src
COPY gateway-app/src gateway-app/src

# Build the application
RUN mvn clean package -DskipTests -B

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine

LABEL maintainer="Spring Gateway Toolkit"
LABEL description="Spring Cloud Gateway with logging, caching, and mock API framework"
LABEL version="1.0.0"

# Create non-root user for security
RUN addgroup -g 1001 gateway && \
    adduser -u 1001 -G gateway -s /bin/sh -D gateway

WORKDIR /app

# Copy the built JAR
COPY --from=builder /build/gateway-app/target/gateway-app.jar app.jar

# Create directories for external configuration
RUN mkdir -p /app/config /app/mocks && \
    chown -R gateway:gateway /app

# Switch to non-root user
USER gateway

# Environment variables with defaults
ENV SERVER_PORT=8080 \
    JAVA_OPTS="-Xms256m -Xmx512m" \
    SPRING_PROFILES_ACTIVE="docker"

# Expose the port
EXPOSE ${SERVER_PORT}

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:${SERVER_PORT}/actuator/health || exit 1

# Run the application
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar app.jar --spring.config.additional-location=optional:file:/app/config/"]
