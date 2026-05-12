# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed
- Default branch renamed from `master` to `main`. CI, CodeQL, and Release workflows triggered/guarded on `main`; README, CONTRIBUTING, and SonarCloud scoping language updated accordingly.
- Baseline bumped to Java 25 LTS. Lombok pinned to 1.18.46 (1.18.30 fails on JDK 25). JaCoCo bumped to 0.8.14 (0.8.12's bundled ASM can't read Java 25 bytecode). `maven-compiler-plugin` pinned to 3.15.0 with explicit `<release>${java.version}</release>` and Lombok wired into `<annotationProcessorPaths>`.
- CI/CD workflows aligned with the `notification-service` standard: concurrency cancellation, JDK matrix, `actions/checkout@v6` + `actions/setup-java@v5`, Maven wrapper (`./mvnw`), surefire upload on failure, separate dependency-analyze job pinned to `maven-dependency-plugin:3.9.0`.
- Docker base images bumped to `eclipse-temurin:25-*-alpine`.
- SonarCloud scoped to `push` events on the default branch and `continue-on-error: true` (it is a hygiene check, not a release gate).
- Documentation reconciled with the code: removed references to `@CacheableRoute` and `@EnableSecurityConfig` (which do not exist); corrected default-`enabled` matrix (caching/conman/security default to `false`); rewrote the description of `@EnableGatewayToolkit` to clarify it is a defaults override, not a feature gate; documented the hardcoded `tenant-id` request header used by Conman; corrected the Conman admin REST endpoint paths; corrected `CachingProperties` defaults (`defaultTtl=86400`, `maxSize=10000`).

### Added
- `.github/dependabot.yml` for Maven, GitHub Actions, and Docker base image.
- `.github/workflows/codeql.yml` with the `security-and-quality` query suite.

### Removed
- `TODO.md` — every class on its checklist already had tests; the file was stale.
- `.github/workflows/release-action.yml` — replaced by `release.yml` with `workflow_dispatch` inputs, branch guard, environment gating, pre-flight verify, and `softprops/action-gh-release`.

## [1.0.0] — 2026-02-15

### Added
- Initial release to Maven Central.
- `LoggingAndCachingWebFilter` with per-route logging rules, optional body capture, and sensitive-header redaction for `Authorization` / `Cookie`.
- Caffeine-backed response caching with per-entry TTL via Caffeine's `Expiry` interface and a `CacheProvider` SPI.
- `Conman` mock-API framework: YAML-driven configuration, multi-tenant lookup, request validation (headers, query params, JSON Schema body), template-based responses, and a runtime admin REST API.
- `SecurityAutoConfiguration` for OAuth2 Resource Server (JWT) and OAuth2 Login flows with hardcoded protection of `/gateway/cache/**` and `/conman/admin/**`.
- `CorsWebFilter` configurable via YAML properties.
- Spring Boot starter (`gateway-starter`) with auto-configuration guarded by `@ConditionalOnProperty` flags.
- Standalone runnable application (`gateway-app`) with Docker support and Spring profiles for Consul, Eureka, and static routes.
- REST management APIs: `/gateway/cache/**` for cache inspection/invalidation and `/conman/admin/**` for mock management.
- CI/CD via GitHub Actions; SonarCloud and JaCoCo coverage wired in.
