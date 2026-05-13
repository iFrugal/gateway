# Versioning Policy

Spring Gateway Toolkit follows [Semantic Versioning 2.0.0](https://semver.org/). The version published to Maven Central is `MAJOR.MINOR.PATCH`. This page documents what each component of that version actually commits us to, so downstream consumers know what they can rely on and what we reserve the right to change.

## Quick summary

| Change | Bump | Example |
|---|---|---|
| Backwards-incompatible API removal or rename | MAJOR | `1.x.y` → `2.0.0` |
| New feature; new public API; deprecation; non-breaking config addition | MINOR | `1.0.x` → `1.1.0` |
| Bug fix; doc fix; internal refactor; dependency patch bump | PATCH | `1.1.0` → `1.1.1` |

`SNAPSHOT` builds (e.g. `1.1.0-SNAPSHOT`) are pre-release; no compatibility guarantees apply.

## What "public API" means here

For a Maven library, "public API" extends past what `javac` calls public. We commit to compatibility on:

1. **`public` Java classes, methods, fields, and annotations.** Adding to them is non-breaking. Removing or renaming is breaking.
2. **`@ConfigurationProperties` prefixes and property names** under `gateway.*`. Adding new properties with sensible defaults is non-breaking. Renaming or removing a property is breaking.
3. **Default behaviour of those properties.** Changing a default value is breaking *if* it would silently flip behaviour for a deployment that was relying on the old default. We try to bump MAJOR for those, or at least document them prominently in the CHANGELOG `Changed` section.
4. **REST endpoints under `/gateway/cache/**` and `/conman/admin/**`** — paths, request/response shapes, status codes. Adding endpoints is non-breaking. Removing or changing existing ones is breaking.
5. **Spring bean names** for beans the toolkit registers (e.g. `conmanHandler`, `caffeineProvider`). Renaming a bean affects anyone using `@Qualifier(...)`, so it's breaking.

We do **NOT** commit to compatibility on:

- Classes marked `@Internal` (Spring's), or classes the docs explicitly flag as implementation details (see [extension-points.md — Not an extension point](extension-points.md#not-an-extension-point-internal-classes)).
- The runtime behaviour of `lazydevs.SerDe.YAML` and other transitive dependencies — those follow their own versioning.
- The exact wire format of structured log lines (the *fields* are part of the API; the JSON layout / field order is not).
- Internal cache-key formats, internal thread scheduling, internal map / collection types.

## What a MAJOR bump means

We aim for MAJOR bumps to be **rare and well-flagged**. A MAJOR release will:

- Land all known breaking changes in a single window, rather than dribbling them out.
- Ship a migration guide as a top-level `MIGRATION-<old>-to-<new>.md` doc with concrete before/after snippets.
- Be preceded by at least one MINOR release that deprecates anything that's about to be removed (where practical — see "Deprecation policy" below).

Examples of what would trigger a MAJOR:

- Renaming a `public` class (e.g. `ConmanServlet` → `ConmanHandler`, which we did *before* `1.1.0` precisely so it wasn't a MAJOR).
- Removing a public configuration property without a backwards-compatible replacement.
- Changing the cache-key algorithm in a way that would invalidate existing entries on upgrade.
- Bumping the minimum Java version (see "Java baseline policy" below).
- Bumping Spring Boot's major version (Spring Boot 3 → 4 will be a MAJOR release here because of the Spring Cloud Gateway artifact rename).

## What a MINOR bump means

MINOR releases are additive. After upgrading from `1.1.0` to `1.2.0`, every existing deployment should keep working without configuration changes. New behaviour is opt-in: new properties default to off, or to behaviour that matches the previous release.

Things that go in MINOR:

- New `@ConfigurationProperties` fields with sensible defaults.
- New REST endpoints under existing path roots.
- New `@Bean` beans that are `@ConditionalOnMissingBean` (so downstream overrides survive).
- `@Deprecated` annotations on existing public API (with `since` set and a replacement documented).
- Internal performance improvements that don't change observable behaviour.

## What a PATCH bump means

PATCH releases fix bugs and update transitive dependencies along their patch line. After upgrading from `1.1.0` to `1.1.1` you should observe at most:

- A bug stops happening that used to happen.
- A security advisory in a transitive dependency stops applying.
- Documentation is more correct.

If you observe anything else, that's a bug in the PATCH release; please open an issue.

## Deprecation policy

Public API marked `@Deprecated(forRemoval = true, since = "X.Y.Z")` will remain available for **at least one MINOR release** after the deprecation lands, before being removed in the next MAJOR.

Example flow:

- `1.1.0` — feature shipped.
- `1.2.0` — `@Deprecated(forRemoval = true, since = "1.2.0")` added; old method continues to work alongside its replacement.
- `1.3.0` (or higher MINOR) — deprecation has been visible for at least one release cycle; deprecated method still works.
- `2.0.0` — method removed; CHANGELOG `Removed (BREAKING)` entry; migration guide updated.

Already-deprecated members in `1.x`:

- `BodyCaptureRequest.getFullBody()` — deprecated `1.1.0`; use `getFullBodyAsync()`.

## Java baseline policy

The minimum Java version is **part of the public contract** and follows this rhythm:

- Stay on an LTS Java release for the lifetime of a MAJOR version line.
- Bump to the next LTS only on a MAJOR boundary.
- Non-LTS Java releases (26, 27, 28...) are not baselines. We test the library against them as a hygiene check, but never require them.

Current baseline: **Java 25 LTS** (introduced in `1.1.0`; was Java 21 LTS in `1.0.x`).

The `dependabot.yml` config ignores non-LTS Docker base image bumps for the same reason.

## Spring Boot baseline policy

Spring Boot's release cadence drives a lot of our pinning decisions because:

- Spring Boot 3.x → 4.x changes the Spring Framework major version, Jakarta EE version, and (for us) the Spring Cloud Gateway artifact name.
- Springdoc, Lombok, and several other deps line up with Spring Boot's major.

We will:

- Stay within one Spring Boot major per toolkit MAJOR line.
- Treat a Spring Boot major bump as a MAJOR for the toolkit too (e.g. `2.0.0` will be the Spring Boot 4 release).
- Update Spring Boot minor/patch versions freely within the toolkit's MINOR/PATCH releases.

## Pre-release / snapshot policy

- `MAJOR.MINOR.PATCH-SNAPSHOT` versions are not deployed to Maven Central; they exist for in-development builds only.
- Anyone using a snapshot via the local Maven repo accepts that the API can change without notice; we don't blame downstream upgrade pain on snapshot consumption.

## What we ask of downstream

- Pin the exact toolkit version in your build (don't use version ranges like `[1.0,2.0)`).
- Read the CHANGELOG when upgrading a MAJOR or MINOR.
- If something documented as public breaks on a MINOR or PATCH upgrade, open an issue — it's our bug.

## Open questions

These are not yet covered by policy and will be flagged here as they come up:

- **Source vs binary compatibility for `Lombok @Data`-generated methods.** Adding a field to a `@Data` class is source-compatible but technically binary-incompatible. We treat field additions on `*Properties` classes as non-breaking; if this bites someone in practice we'll revisit.
- **Spring `RecordsBeanInfo` and the toolkit's POJO-vs-record stance.** No POJOs are currently records; if we convert a few in a future MINOR, getters change name (`isEnabled()` → `enabled()`). We'll bump MAJOR for any such conversion or supply a deprecation period via wrapper classes.
