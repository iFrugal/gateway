# Contributing to Spring Gateway Toolkit

Thank you for your interest in contributing! This document provides guidelines for contributing to the project.

## How to Contribute

### Reporting Bugs

- Open an issue on GitHub with the "bug" label.
- Include steps to reproduce, expected behavior, and actual behavior.
- Include your Java version, Spring Boot version, and OS.

### Suggesting Features

- Open an issue on GitHub with the "enhancement" label.
- Describe the use case and why this feature would be valuable.

### Submitting Pull Requests

1. Fork the repository and create a feature branch from `main`.
2. Write your code following the existing code style.
3. Add or update unit tests for your changes.
4. Ensure all tests pass: `mvn clean verify`.
5. Update documentation (README, Javadoc) if applicable.
6. Submit a pull request with a clear description of the changes.

## Development Setup

### Prerequisites

- Java 25 LTS or later
- Maven 3.9+
- Docker (optional, for integration testing)

### Building

```bash
# Build all modules
mvn clean install

# Build without tests (faster iteration)
mvn clean install -DskipTests

# Run only the standalone app
mvn spring-boot:run -pl gateway-app
```

### Project Structure

- `gateway-core` - Core library (reusable JAR) with filters, caching, Conman, and configuration.
- `gateway-starter` - Spring Boot Starter providing auto-configuration.
- `gateway-app` - Standalone runnable gateway application.

### Code Style

- Follow standard Java conventions.
- Use Lombok annotations where appropriate.
- Write Javadoc for all public classes and methods.
- Use `@Slf4j` for logging (via Lombok).
- Configuration properties should include example YAML in Javadoc.

### Testing

- Unit tests go in `src/test/java` under the respective module.
- Use Spring Boot Test for integration tests.
- Use `reactor-test` for reactive pipeline testing.
- Aim for meaningful test coverage on all new code.

## Code of Conduct

Please see [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).

## License

By contributing, you agree that your contributions will be licensed under the Apache License 2.0.
