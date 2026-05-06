# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Request/Response logging with configurable path and method matching
- Caffeine-based response caching with per-path TTL rules
- Conman mock API framework with YAML configuration, multi-tenancy, request validation, and template responses
- OAuth2 security with JWT resource server and login flow
- CORS configuration via YAML properties
- Spring Boot Starter for auto-configuration
- Standalone gateway application with Docker support
- Service discovery profiles for Consul, Eureka, and static routes
- Cache management REST API (`/gateway/cache/**`)
- Conman admin REST API (`/conman/admin/**`)
- CI/CD with GitHub Actions (build + Maven Central release)
- Structured JSON logging for Docker/Kubernetes environments

## [1.0.0] - Upcoming

### Planned
- Initial stable release to Maven Central
- Unit and integration test suite
- JaCoCo coverage reporting
- SonarCloud quality gate integration
