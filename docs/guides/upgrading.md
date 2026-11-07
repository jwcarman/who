# Upgrading

## Upgrading to 0.11.0

0.11.0 adds email-link sign-in and sessions. It also changes every timestamp column to `TIMESTAMP WITH TIME ZONE`.

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
- **Liquibase.** The long YAML changelog in the old guide is gone. Include `classpath:org/jwcarman/who/db/changelog-all.xml` instead. See [Liquibase](database.md#liquibase). Do not run it over tables that were created by hand without `changelog-sync` first.
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
