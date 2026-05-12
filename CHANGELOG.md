# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed (BREAKING — public API)
- **Renamed `com.github.ifrugal.gateway.core.conman.ConmanServlet` → `ConmanHandler`.** The original name was historical and misleading: the class is a reactive `RouterFunction` handler returning `Mono<ServerResponse>` and has nothing to do with `jakarta.servlet.Servlet`. The Spring `@Bean` method `conmanServlet(...)` in `GatewayToolkitAutoConfiguration` was renamed to `conmanHandler(...)`. Downstream consumers holding a binary reference to `ConmanServlet` from `1.0.x` need to update imports. The two public constructors and the `service(ServerRequest)` method signature are unchanged. **No deprecation shim shipped** — adoption of `1.0.x` is minimal and the rename is best done before `1.1.0` cuts to Maven Central.

### Removed (BREAKING — public API)
- **Removed `@EnableGatewayToolkit` annotation and `GatewayToolkitImportSelector`.** The annotation only seeded `gateway.{logging,caching,conman}.enabled` defaults at the lowest precedence; the starter's auto-configuration runs unconditionally via `META-INF/spring/...AutoConfiguration.imports` regardless of whether the annotation was present. It looked like a feature gate but wasn't, and the README/architecture docs needed multiple paragraphs to explain that. Configure features via `application.yml`, environment variables, or command-line flags. Downstream code that previously had `@EnableGatewayToolkit(enableCaching = false)` should set `gateway.caching.enabled: false` in YAML instead. The `com.github.ifrugal.gateway.core.annotation` package is empty after this change.

### Fixed
- Removed `${env.GITHUB_TOKEN}` placeholder from the `<scm>` `<connection>` and `<developerConnection>` URLs in the root `pom.xml`. Maven publishes the source POM (not a resolved one), so the placeholder ended up as visible noise in every published Maven Central artifact's `pom.xml` — and would have become a real-token-leak risk the moment a maintainer ran `mvn release:perform` locally with that env var exported. Replaced with the standard HTTPS form; CI auth is injected at runtime via `actions/checkout`'s extraheader credential plus the existing `git remote set-url` step in `release.yml`.
- Reverted a batch of Dependabot bumps that had broken the build. Spring Boot back to 3.5.10 (had been bumped to 4.0.6), Spring Cloud back to 2025.0.0 (had been bumped to 2025.1.1 which renames `spring-cloud-starter-gateway` and broke the reactor), springdoc back to 2.8.15 (the 3.x line targets Spring Boot 4), `json-schema-validator` back to 1.5.6 (the 3.x rewrite changes the `JsonSchema`/`JsonSchemaFactory`/`SpecVersionDetector` API). Docker builder image rolled back from non-LTS Java 26 to the pinned `maven:3.9-eclipse-temurin-25-alpine`. Internal libraries (`lazydevs.version` 1.0.46, `ifrugal-parent` 1.0.15) and the action minor bump (`crazy-max/ghaction-import-gpg` v7) are kept.

### Changed
- `dependabot.yml` tightened: ignores major-version bumps for Spring Boot, Spring Cloud, springdoc, and `json-schema-validator`; pins Docker base images to the Java 25 LTS line so non-LTS bumps aren't proposed.
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
