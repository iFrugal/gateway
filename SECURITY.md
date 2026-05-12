# Security Policy

## Supported Versions

| Version | Supported          |
|---------|--------------------|
| 1.x     | Yes                |

## Reporting a Vulnerability

If you discover a security vulnerability in Spring Gateway Toolkit, please report it responsibly.

**Do NOT open a public GitHub issue for security vulnerabilities.**

Instead, please email: **abhijeet.techrepo@gmail.com**

Include:

- Description of the vulnerability
- Steps to reproduce
- Potential impact
- Suggested fix (if any)

## Response Timeline

- **Acknowledgment:** Within 48 hours of report.
- **Assessment:** Within 1 week.
- **Fix & Release:** Depending on severity, typically within 2 weeks for critical issues.

## Security Best Practices for Users

When deploying Spring Gateway Toolkit in production:

1. **Enable security:** Set `gateway.security.enabled=true` and configure OAuth2.
2. **Protect admin endpoints:** The `/gateway/cache/**` and `/conman/admin/**` endpoints require authentication when security is enabled. Do not add them to `guest-allowed-paths` in production.
3. **Use environment variables** for secrets (`OAUTH2_CLIENT_ID`, `OAUTH2_CLIENT_SECRET`, etc.) rather than hardcoding them in YAML files.
4. **Run as non-root** in Docker (the provided Dockerfile already does this).
5. **Keep dependencies updated** by regularly running `mvn versions:display-dependency-updates`.
