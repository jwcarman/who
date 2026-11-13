# Upgrading

## Upgrading to 0.16.0

0.16.0 has no schema or property changes. One small break:

- **`WhoAuthenticationToken` and `SignInFailedException` are `final`.** Both called an inherited
  method from their constructor, which a subclass could observe half-built. If you extended
  either one, wrap it or keep the extra data beside it instead.

Optional: to keep sign-in emails in the request's trace, register a `TaskDecorator` such as
Spring's `ContextPropagatingTaskDecorator`; see
[Tracing the send](email-links.md#tracing-the-send).

## Upgrading to 0.15.0

0.15.0 adds a typed sign-in code to every sign-in email. It changes the schema, a required property, and several signatures.

- **Upgrade the `who_email_link_token` table.** It gained `code_hash VARCHAR(64) NOT NULL` and `code_attempts INTEGER NOT NULL`. Sign-in links that are outstanding during the upgrade are dropped; people request a new one.
    - **Liquibase:** nothing to do. The new changeSet `who-email-link-token-2` runs at startup.
    - **Flyway, or the bundled scripts (`who.initialize-schema`):** run this once, for example as a Flyway migration. The bundled script does not change a table that already exists.

        ```sql
        DELETE FROM who_email_link_token;
        ALTER TABLE who_email_link_token
            ADD COLUMN code_hash     VARCHAR(64) NOT NULL,
            ADD COLUMN code_attempts INTEGER     NOT NULL;
        ```
    - **This is not a rolling upgrade.** Stop the 0.14.0 instances first, and apply the schema change (Liquibase or the SQL) before or as 0.15.0 starts. If the columns are missing, sign-in emails silently stop: `/ott/generate` still answers `202`, and only a WARN is logged.
- **Set `who.email-link.code-secret`.** It is required when email-link sign-in is on, at least 32 characters, and the same on every instance. Startup fails without it. See [Code secret](email-links.md#code-secret).
- **`EmailLinkSender` takes an `EmailLinkMessage`.** The method is now `send(EmailLinkMessage message)` instead of `send(String address, String link)`. The message carries `address`, `link`, `code` and `expiresAt`, and `formattedCode()` gives the grouped code. Update custom senders to include the code. See [Turn it on](email-links.md#turn-it-on).
- **`POST /login/ott` takes two body shapes.** `{"token": "..."}` as before, or `{"email": "...", "code": "..."}`.
- **New properties.** `who.email-link.code-length` (default 6), `code-max-attempts` (default 5) and `code-secret`. The default subject is now `Your sign-in link and code`.
- **Changed signatures.** Code that implements or calls these must change:
    - `EmailLinkTokenRepository`: `issue` takes the code hash, and `attemptCode` and `consumeCode` are new;
    - `EmailLinkAuthenticationProvider` is built with `(repository, settings, whoService, clock)`;
    - `WhoOneTimeTokenService` is built with `(clock, tokenTtl)`;
    - `WhoOneTimeTokenService.consume` now throws `UnsupportedOperationException`. who's `EmailLinkAuthenticationProvider` redeems sign-ins, so an application that reuses that bean with Spring's default OTT provider must switch to who's provider.

## Upgrading to 0.14.0

0.14.0 stops who from writing error responses. who now throws exceptions and your application formats them (see [Errors](errors.md)). There are no schema changes.

- **No more `{"error": …}` bodies.** Sign-in failures, passkey registration errors, signed-out requests and denied requests no longer answer with `{"error":"invalid_token"}`, `{"error":"invalid_credential"}`, `{"error":"reauthentication_required"}` or `{"error":"invalid_registration"}`. Each becomes an exception that reaches your `@RestControllerAdvice`. Without an advice, the status stays the same (401, 400 or 403) and the body is your application's default error body. A client that read `error` should switch to your application's format.
- **Your advice now sees who's errors.** Errors raised inside Spring Security's filters used to bypass your `@RestControllerAdvice`; now they reach it. An advice with a catch-all `@ExceptionHandler(Exception.class)` would answer every signed-out request and failed sign-in with its own status, often a 500. Map who's exceptions (see [Errors](errors.md)) before upgrading, or keep the catch-all from matching them. An advice limited by `basePackages`, `assignableTypes` or `annotations` never sees them; who's errors need a global advice.
- **New entry point and access-denied handler.** `WhoWebSecurity.passwordless()` now installs an entry point and an access-denied handler that hand off `NotSignedInException` and `AccessDeniedException`. They replace the bare 401 and Spring's empty 403. A chain that set its own `exceptionHandling` after applying `passwordless()` keeps its own.
- **`JsonAuthenticationHandlers`.** The constructor is now `(JsonMapper, WhoExceptionHandoff)`, and `failure(String)` is gone; `failure()` hands off a `SignInFailedException`.
- **`PasskeyRegistrationGuardFilter`.** Its constructor takes a `WhoExceptionHandoff` instead of a `JsonMapper`.
- **`WhoMeController`.** `GET /who/me` throws `NotSignedInException` when no one is signed in, instead of answering a bare `401`.

## Upgrading to 0.13.0

0.13.0 is additive: `WhoService.deactivate(identityId)` and `reactivate(identityId)` take an identity
out of service and put it back, and `who-test` gains `WhoMockMvcBrowser.performWithoutCsrf`. Nothing
needs to change, and there are no schema changes.

## Upgrading to 0.12.0

0.12.0 makes sessions ready for Spring Session, adds RBAC lookups and a test module, and has five changes that can affect existing code. There are no schema changes.

- **No saved requests.** `WhoWebSecurity.passwordless()` now uses a `NullRequestCache`. A request denied with `401` is no longer saved in the session, so sign-in no longer leads to a redirect to a saved request. An SPA never relied on it, because it receives a plain `401`. If your chain redirected to the saved request, keep the target in your app instead. See [Sessions](sessions.md#no-saved-requests).
- **`EmailLinkIssuer.issue` returns a value.** It returns an `EmailLinkOutcome` instead of `void`. Source that calls it still compiles, but code built against 0.11 must be recompiled, because the method's signature in the class file changed. A subclass or a mock that overrides `issue` must change its return type. See [Sending a link from your own code](email-links.md#sending-a-link-from-your-own-code).
- **`RoleAlreadyExistsException`.** Creating a role whose name is taken now throws `RoleAlreadyExistsException` instead of a plain `IllegalArgumentException`, with a message that does not name the role. It extends `IllegalArgumentException`, so existing `catch` blocks keep working.
- **New RBAC repository methods.** `RoleRepository` gains `findAll()` and `IdentityRoleRepository` gains `findIdentityIdsByRoleId(UUID)`. An app with its own implementation of either interface must implement them, or it will not compile. The JDBC repositories who ships already do.
- **A new session attribute.** The session now also holds the identity id (as a string) under Spring Session's principal-name index attribute. With Spring Session, `FindByIndexNameSessionRepository.findByPrincipalName(identityId.toString())` finds the sessions of an identity. A test that asserts the exact attributes of a session must expect it. Sessions created before the upgrade have no such attribute until the user signs in again. See [Sessions](sessions.md#sign-out-everywhere).
- **New.** `RbacService.findRole`, `listRoles` and `findIdentityIdsWithRole`; the `who-test` module ([Testing](testing.md)); native serialization hints for sessions, so apps that added their own can drop them ([Native Images](native.md)).

## Upgrading to 0.11.0

0.11.0 adds email-link sign-in, passkeys and sessions. It also changes every timestamp column to `TIMESTAMP WITH TIME ZONE`.

### Convert the timestamp columns first

Existing databases created by 0.10.0 or earlier must run these statements before the upgrade. Who 0.11.0 reads and writes timestamps as UTC values, and reading the old `TIMESTAMP` columns may fail. The `CREATE TABLE IF NOT EXISTS` schema does not change existing tables.

The old columns hold local time without a zone. Use the time zone the JVM ran in, here `UTC`:

```sql
ALTER TABLE who_identity
    ALTER COLUMN created_at TYPE timestamptz USING created_at AT TIME ZONE 'UTC',
    ALTER COLUMN updated_at TYPE timestamptz USING updated_at AT TIME ZONE 'UTC';

ALTER TABLE who_enrollment_token
    ALTER COLUMN created_at  TYPE timestamptz USING created_at  AT TIME ZONE 'UTC',
    ALTER COLUMN expires_at  TYPE timestamptz USING expires_at  AT TIME ZONE 'UTC',
    ALTER COLUMN redeemed_at TYPE timestamptz USING redeemed_at AT TIME ZONE 'UTC';
```

Replace `UTC` with the zone of the JVM (for example `Europe/Berlin`) if it was not UTC. These statements are for PostgreSQL. New schemas spell the type `TIMESTAMP WITH TIME ZONE`, which H2 and PostgreSQL both accept.

### Other changes

- **New tables.** Email-link sign-in adds `who_email_credential` and `who_email_link_token`. The bundled schema creates them. If you manage the schema with Flyway, copy them from [Database Setup](database.md#flyway).
- **Passkeys are opt-in.** The new `who-passkey` module adds the tables `who_passkey_user` and `who_passkey_credential`. The bundled schema creates them. If you manage the schema with Flyway, copy them from [Database Setup](database.md#flyway). Passkey storage is PostgreSQL only. Nothing changes until you set `who.passkey.rp-id`. See [Passkeys](passkeys.md).
- **Schema files renamed.** Each bundled schema file now starts with its module name. If you point `spring.sql.init.schema-locations` or your own tooling at them, update the paths:
    - `org/jwcarman/who/jdbc/who-jdbc-schema.sql`
    - `org/jwcarman/who/rbac/who-rbac-schema.sql`
    - `org/jwcarman/who/jwt/who-jwt-schema.sql`
    - `org/jwcarman/who/enrollment/who-enrollment-schema.sql`
    - `org/jwcarman/who/apikey/who-apikey-schema.sql`
    - `org/jwcarman/who/emaillink/who-email-link-schema.sql`
    - `org/jwcarman/who/passkey/who-passkey-schema.sql` (new)
- **Liquibase.** The long YAML changelog in the old guide is gone. Include `classpath:org/jwcarman/who/db/who-changelog.xml` instead. See [Liquibase](database.md#liquibase). Do not run it over tables that were created by hand without `changelog-sync` first.
- **Email links are opt-in.** Nothing changes for an application that uses only JWT or API keys. To turn them on, see [Email Links](email-links.md).
- **Sessions.** `WhoWebSecurity.passwordless()` is new and applies only to chains that call it. Other chains are unchanged.

## Upgrading to 0.10.0

0.10.0 is a security release. Some changes break existing code or data.

- **API key filter wiring.** `ApiKeyAuthenticationFilter` is no longer registered globally with the servlet container. Add it to each chain that should accept API keys: `http.with(WhoApiKeySecurity.apiKey(), Customizer.withDefaults())`. The filter skips requests that are already authenticated. See [Wiring the filter](api-keys.md#wiring-the-filter).
- **Method security is on by default.** `@RequiresPermission` is now enforced without any setup. Set `who.method-security.enabled=false` to turn it off. An `@EnableMethodSecurity` in your own configuration is respected.
- **`ApiKeyCredentialRepository.deleteById` returns `boolean`.** It returns `true` if a key was deleted. Update custom implementations. To revoke a key, call `ApiKeyService.revoke(UUID)`.
- **New key and token format.** New API keys are `who_` plus 43 URL-safe characters (256 random bits). Enrollment tokens use the same random source. Existing API keys keep working. Pending enrollment tokens issued before 0.10.0 are predictable (the token value followed from its id), so revoke them and issue new ones:

  ```sql
  UPDATE who_enrollment_token SET status = 'REVOKED' WHERE status = 'PENDING';
  ```
- **Enrollment exceptions.** `enroll` throws `EnrollmentIdentityInactiveException` for an identity that is not active, and `CredentialAlreadyLinkedException` for a credential that is already linked. A token is single use, even under concurrent requests. `EnrollmentTokenNotFoundException` no longer contains the token value, and `EnrollmentToken.toString()` masks the value.
- **JWT claims.** A token with no `iss` or no `sub` claim now gets 401 instead of 500. Validate the issuer too. See [Configure JWT](jwt.md).
- **Foreign key on `who_identity_role`.** `who_identity_role.identity_id` now references `who_identity(id)` with `ON DELETE CASCADE`. Deleting an identity removes its role assignments, and assigning a role to an identity that is not in `who_identity` now fails. The `CREATE TABLE IF NOT EXISTS` schema does not change existing tables, so on an existing database run this yourself. Delete orphan rows first, or the constraint fails:

```sql
DELETE FROM who_identity_role
WHERE identity_id NOT IN (SELECT id FROM who_identity);

ALTER TABLE who_identity_role
    ADD CONSTRAINT fk_identity_role_identity
    FOREIGN KEY (identity_id) REFERENCES who_identity(id) ON DELETE CASCADE;
```
