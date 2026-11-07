# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.10.0] - 2026-11-07

A security release. Read [Upgrading to 0.10.0](https://jwcarman.github.io/who/guides/upgrading/)
before you upgrade.

### Breaking changes

- `ApiKeyAuthenticationFilter` is no longer registered with the servlet container. Add it to
  each chain that accepts API keys: `http.with(WhoApiKeySecurity.apiKey(), Customizer.withDefaults())`.
- Method security is on by default, so `@RequiresPermission` is enforced with no setup. Set
  `who.method-security.enabled=false` to turn it off.
- `ApiKeyCredentialRepository.deleteById` returns `boolean`.
- `who_identity_role.identity_id` references `who_identity(id)` with `ON DELETE CASCADE`.
  Existing databases need the foreign key added by hand (SQL in the upgrade guide).
- Pending enrollment tokens issued before 0.10.0 are predictable. Revoke them and issue new ones
  (SQL in the upgrade guide).

### Added

- `Secrets.token()`: 256 random bits, URL-safe Base64. The one source of secret tokens.
- `ApiKeyService.revoke(UUID)` deletes a key and its identity link.
- `EnrollmentIdentityInactiveException` and `CredentialAlreadyLinkedException`.
- `who.method-security.enabled` (default `true`).

### Fixed

- Enrollment token values were predictable: they followed from the token's UUIDv7 id. They now
  come from `Secrets.token()`.
- An enrollment token could be redeemed twice by concurrent requests. Redemption is now one
  atomic conditional update, and a failed link rolls it back.
- `enroll` accepted inactive identities and credentials already linked to another identity.
- The API-key filter ran on every request, outside Spring Security's chains.
- A JWT with no `iss` or no `sub` gave 500. It now gives 401.
- `EnrollmentTokenNotFoundException` and `EnrollmentToken.toString()` exposed token values.
- Revoking a redeemed token cleared its redemption time.
- Deleting an identity left its role assignments behind.

### Documentation

- A documentation site at [jwcarman.github.io/who](https://jwcarman.github.io/who/), with guides
  and per-release API docs. The README is now a short overview.
- The `PermissionsResolver` Javadoc now says Who uses exactly one resolver.

### Requirements

- Spring Boot 4.1 (built against 4.1.2).
- Java 25+ and PostgreSQL 14+, unchanged.

[Unreleased]: https://github.com/jwcarman/who/compare/0.10.0...HEAD
[0.10.0]: https://github.com/jwcarman/who/releases/tag/0.10.0
