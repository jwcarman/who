# Upgrading

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
