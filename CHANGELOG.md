# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Breaking changes

- `WhoWebSecurity.passwordless()` uses `NullRequestCache`: a request denied with `401` is no longer
  saved in the session, so there is no post-login redirect to the saved request.

### Changed

- Creating a role whose name is taken throws `RoleAlreadyExistsException` (still an
  `IllegalArgumentException`) with a message that does not name the role.
- `EmailLinkIssuer.issue` returns an `EmailLinkOutcome` instead of `void`. `/ott/generate` still
  answers `202` at once and ignores it.

### Added

- `EmailLinkOutcome` (`SENT`, `UNKNOWN_ADDRESS`, `INACTIVE_IDENTITY`, `RESEND_SUPPRESSED`,
  `SEND_FAILED`) tells a caller of `EmailLinkIssuer.issue` whether the email went out.
- `RbacService.findRole(String)` and `findRole(Enum)` return an `Optional<Role>`;
  `listRoles()` returns every role sorted by name; `findIdentityIdsWithRole(Role)` returns the
  ids of the identities that hold a role.
- The session holds the identity id under Spring Session's principal-name index attribute, so
  `FindByIndexNameSessionRepository.findByPrincipalName(identityId)` finds every session of an
  identity. who needs no Spring Session dependency.

## [0.11.0] - 2026-11-07

Read [Upgrading](https://jwcarman.github.io/who/guides/upgrading/) before you upgrade. Existing
databases must convert their timestamp columns first.

### Breaking changes

- Every timestamp column is `TIMESTAMP WITH TIME ZONE`, and repositories bind `OffsetDateTime` in
  UTC. Databases created by 0.10.0 or earlier need `ALTER TABLE` statements (in the upgrade guide)
  before the upgrade, or reading timestamps may fail.
- The bundled schema files are renamed to `who-<module>-schema.sql`:
  `org/jwcarman/who/jdbc/who-jdbc-schema.sql`, `rbac/who-rbac-schema.sql`, `jwt/who-jwt-schema.sql`,
  `enrollment/who-enrollment-schema.sql`, `apikey/who-apikey-schema.sql` and
  `emaillink/who-email-link-schema.sql`. Update any `spring.sql.init.schema-locations` that names them.
- Liquibase users who copied the old YAML changelog should switch to including
  `classpath:org/jwcarman/who/db/who-changelog.xml`, on a fresh schema or after `changelog-sync`.

### Added

- `who-email-link`: sign-in by an emailed link, on Spring Security's one-time-token login.
  `EmailCredentialService` (`register`, `findByAddress`, `revoke`), the `EmailLinkSender` SPI, and
  `MailEmailLinkSender` for `JavaMailSender`. It is opt-in: set `who.email-link.link-base-url`.
- `who.email-link.token-ttl`, `resend-interval`, `from` and `subject` properties.
- `WhoWebSecurity.passwordless()`: session sign-in for a browser chain, with `csrf.spa()`, a plain
  401 for unauthenticated requests, and `/logout` answering 204. It fails at startup if the chain
  disables CSRF or is stateless.
- `GET /who/me` returns `{"identityId", "permissions"}` for the signed-in caller.
- Liquibase changelogs for every module, and `who-changelog.xml` in `who-autoconfigure`.
- The [Email Links](https://jwcarman.github.io/who/guides/email-links/) guide.
- `who-passkey`: sign-in with passkeys, on Spring Security's WebAuthn support and its default
  endpoints. `PasskeyService` (`list`, `revoke`), the `PasskeyAddedNotifier` SPI and
  `MailPasskeyAddedNotifier`. It is opt-in: set `who.passkey.rp-id`. Storage is PostgreSQL only
  (tables `who_passkey_user` and `who_passkey_credential`).
- `who.passkey.rp-id`, `rp-name`, `allowed-origins`, `registration-window` and `from` properties.
- The [Passkeys](https://jwcarman.github.io/who/guides/passkeys/) guide.
- Native image support with no app configuration. Every module ships GraalVM reachability
  metadata for its Liquibase changelogs and schema script, and `who-passkey` registers Spring AOT
  hints for the Spring Security WebAuthn and webauthn4j type families.
- The [Native Images](https://jwcarman.github.io/who/guides/native/) guide.
- `WhoService.deleteIdentity(UUID)`: deletes an identity with its email, JWT and API key
  credentials and their links, in one transaction. Deleting `who_identity` directly left those
  credential rows behind.
- `WhoService.purgeOrphanedCredentials()`: deletes credentials linked to no identity, such as those
  left by a direct delete. It also deletes JWT credentials saved for an enrollment not yet redeemed.
- The `CredentialRemover` SPI. The JDBC email, JWT and API key repositories implement it, and the
  autoconfiguration passes every `CredentialRemover` bean to `WhoService`.

### Changed

- `EmailCredentialService.register` accepts an address that is registered but linked to no
  identity: it deletes that orphaned credential (and any outstanding sign-in link) and registers
  a new one for the identity. It returns the existing credential when the address
  is already the identity's own. It still throws `EmailAddressTakenException` when another identity
  holds the address.
- `CredentialIdentityRepository` has a new method, `findCredentialIdsByIdentityId(UUID)`. Custom
  implementations must add it.
- The autoconfiguration declares the email, JWT and API key repository beans with their JDBC
  types. They are still replaced by any bean of the repository interface.
- `WhoAuthenticationToken.getName()` returns the identity id, for every chain (sessions, JWTs and
  API keys). Spring's WebAuthn support keys on it. Anything that reads `authentication.name`, such
  as SpEL expressions or audit logs, now sees the identity id.
- The session keeps only the identity and credential ids. Who re-resolves the principal on every
  request, so role changes and deactivation take effect on the next request.
- The Liquibase guide now includes `who-changelog.xml` instead of a copied changelog.
- Who's JDBC repositories are no longer annotated `@Repository`, so no exception-translation proxy
  is created for them (which Spring AOT could not generate natively). Exceptions are still
  translated: `JdbcClient` throws Spring's `DataAccessException` hierarchy. An app that component-scans
  `org.jwcarman.who` no longer picks these classes up; who's autoconfiguration creates them.
- With `who.initialize-schema: never`, who no longer looks up its schema scripts.

### Fixed

- Deleting an identity left its email, JWT and API key credentials behind, and an orphaned email
  address could never be registered again. Use `WhoService.deleteIdentity`; registration now
  reclaims an orphaned address, and `purgeOrphanedCredentials()` cleans up old data.
- A native image could not start: who's changelogs and schema files were missing from the image,
  and who's `@Repository` beans needed proxies Spring AOT could not generate.

### Requirements

- Spring Boot 4.1 (built against 4.1.2). Passkeys use `spring-security-webauthn` with
  `webauthn4j-core` 0.32.0.RELEASE, pinned by who.
- Java 25+ and PostgreSQL 14+, unchanged.

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

[Unreleased]: https://github.com/jwcarman/who/compare/0.11.0...HEAD
[0.11.0]: https://github.com/jwcarman/who/releases/tag/0.11.0
[0.10.0]: https://github.com/jwcarman/who/releases/tag/0.10.0
