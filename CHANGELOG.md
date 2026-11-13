# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Breaking changes

- Every sign-in email carries a typed code as well as the link. `POST /login/ott` accepts `{"token": "..."}` or `{"email": "...", "code": "..."}`. Using one ends the other.
- `EmailLinkSender.send` takes an `EmailLinkMessage(address, link, code, expiresAt)` instead of `(address, link)`.
- `who.email-link.code-secret` is required when email-link sign-in is on, at least 32 characters.
- `who_email_link_token` gained `code_hash` and `code_attempts` (both `NOT NULL`). There is no upgrade path: recreate the database.
- `EmailLinkTokenRepository`, `EmailLinkAuthenticationProvider` and `WhoOneTimeTokenService` have changed signatures.
- The default subject is `Your sign-in link and code`.

### Added

- The code uses the Crockford base32 alphabet, is shown grouped (`K7F-2QM`), and is read without regard to case, spaces or dashes. `O` reads as `0`, and `I` and `L` as `1`.
- After `who.email-link.code-max-attempts` wrong codes (default 5) the code is burnt; the link still works.
- `who.email-link.code-length` (default 6, at least 6), `who.email-link.code-max-attempts` and `who.email-link.code-secret`.
- `EmailLinkMessage.formattedCode()`.

## [0.14.0] - 2026-11-10

### Breaking changes

- who no longer writes error responses. It throws exceptions and hands those raised in Spring Security's filters to the application's Spring MVC exception resolvers, so the application's `@RestControllerAdvice` formats every error. New exceptions in `org.jwcarman.who.spring.security.web`:
  - `SignInFailedException` (401), with the fixed message `"Sign-in failed"`;
  - `NotSignedInException` (401);
  - `ReauthenticationRequiredException` (401);
  - `InvalidPasskeyRegistrationException` (400).
- The `{"error":"invalid_token"}`, `{"error":"invalid_credential"}`, `{"error":"reauthentication_required"}` and `{"error":"invalid_registration"}` bodies are gone. Without an application mapping, who calls `sendError` with the status above.
- `JsonAuthenticationHandlers` takes `(JsonMapper, WhoExceptionHandoff)`, and `failure(String)` is removed.
- `PasskeyRegistrationGuardFilter` takes a `WhoExceptionHandoff` instead of a `JsonMapper`.
- `GET /who/me` throws `NotSignedInException` when signed out.
- `WhoWebSecurity.passwordless()` installs an entry point and an access-denied handler that hand the exception off, instead of a bare 401 and Spring's empty 403.
- Errors raised in the filters now reach the application's advice. A catch-all `@ExceptionHandler(Exception.class)` would answer them with its own status (often 500): map who's exceptions before upgrading. Only a global advice sees them, not one limited by `basePackages`, `assignableTypes` or `annotations`.

### Added

- `WhoExceptionHandoff`, with its `Resolver` interface. who's autoconfiguration builds one over the application's `handlerExceptionResolver` bean.
- The [Errors](docs/guides/errors.md) guide.

### Changed

- The release build uses central-publishing-maven-plugin 0.12.0, which Maven 3.10 needs; earlier versions produce bundles Maven Central rejects.

## [0.13.0] - 2026-11-08

### Added

- `WhoService.deactivate(identityId)` and `reactivate(identityId)` take an identity out of service
  and put it back. A deactivated identity keeps its credentials, roles and history, but no credential
  resolves to it: its next request is unauthenticated, its sign-in links are refused
  (`INACTIVE_IDENTITY`) and its passkeys no longer sign in. Both return `false` for an unknown
  identity and change nothing when the identity is already in that state.
- `WhoMockMvcBrowser.performWithoutCsrf(request)` in `who-test` sends a request as a forged
  cross-site request would look: the browser's session and cookies, but no `X-XSRF-TOKEN` header and
  no token pre-fetch. Use it to prove an endpoint rejects unsafe requests that lack the CSRF header.

## [0.12.0] - 2026-11-07

### Breaking changes

- `WhoWebSecurity.passwordless()` uses `NullRequestCache`: a request denied with `401` is no longer
  saved in the session, so there is no post-login redirect to the saved request.
- `EmailLinkIssuer.issue` returns an `EmailLinkOutcome` instead of `void`. Source still compiles, but
  code built against 0.11 must be recompiled, and an override must change its return type.
  `/ott/generate` still answers `202` at once and ignores the outcome.
- `RoleRepository.findAll()` and `IdentityRoleRepository.findIdentityIdsByRoleId(UUID)` are new
  abstract methods, so an app with its own implementation of either interface must implement them.

### Added

- `who-bom`: a bill of materials that aligns the versions of every published who module. Import it,
  then declare `who-spring-boot-starter` and `who-test` without versions. It manages only who's own
  artifacts, not Spring Boot's.
- The session holds the identity id under Spring Session's principal-name index attribute, so
  `FindByIndexNameSessionRepository.findByPrincipalName(identityId.toString())` finds every session of
  an identity ("sign out everywhere"). who needs no Spring Session dependency.
- `RbacService.findRole(String)` and `findRole(Enum)` return an `Optional<Role>`; `listRoles()`
  returns every role sorted by name; `findIdentityIdsWithRole(Role)` returns the ids of the
  identities that hold a role.
- `EmailLinkOutcome` (`SENT`, `UNKNOWN_ADDRESS`, `INACTIVE_IDENTITY`, `RESEND_SUPPRESSED`,
  `SEND_FAILED`) tells a caller of `EmailLinkIssuer.issue` whether the email went out.
- `who-test` (test scope): `WhoTestSessions.signedIn(identityId, credentialId)` returns a
  `MockHttpSession` that who resolves from the database on the next request, and `WhoMockMvcBrowser`
  wraps a `MockMvc` with one session and its cookies, echoing the `XSRF-TOKEN` cookie into
  `X-XSRF-TOKEN` on unsafe requests as an SPA does with `csrf.spa()`. With Spring Session,
  `signedInAs` saves the session in the app's `SessionRepository` and holds its cookie. Overloads
  take the sign-in `Instant`, for apps with a fixed `Clock`.
- Native serialization hints for sessions (Spring Session JDBC: `WhoSession` and what it holds) and
  for passkey ceremony options, so apps need no native configuration of their own.

### Changed

- Creating a role whose name is taken throws `RoleAlreadyExistsException` (still an
  `IllegalArgumentException`) with a message that does not name the role.

### Documentation

- New [Sessions](https://jwcarman.github.io/who/guides/sessions/) and
  [Testing](https://jwcarman.github.io/who/guides/testing/) guides; the Upgrading, Native Images,
  RBAC and Email Links guides cover the 0.12 changes.
- The Passkeys guide no longer says `anyRequest().authenticated()` protects
  `POST /webauthn/register/options`: Spring runs that filter before the authorization filter, and
  who's `PasskeyRegistrationGuardFilter` protects it.

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

[Unreleased]: https://github.com/jwcarman/who/compare/0.13.0...HEAD
[0.14.0]: https://github.com/jwcarman/who/releases/tag/0.14.0
[0.13.0]: https://github.com/jwcarman/who/releases/tag/0.13.0
[0.12.0]: https://github.com/jwcarman/who/releases/tag/0.12.0
[0.11.0]: https://github.com/jwcarman/who/releases/tag/0.11.0
[0.10.0]: https://github.com/jwcarman/who/releases/tag/0.10.0
