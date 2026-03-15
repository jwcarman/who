# README: document API key authentication

## What to build

Add an API key authentication section to `README.md` covering setup, key generation,
and Spring Security wiring. This is a documentation-only change.

### Content to add

Add a new **API Key Authentication** section after the JWT configuration section.
Cover:

1. **Schema setup** — add `classpath:org/jwcarman/who/apikey/schema.sql` to the
   `spring.sql.init.schema-locations` list (or equivalent Flyway/Liquibase DDL).
   Include the full DDL so users can copy it:
   ```sql
   CREATE TABLE IF NOT EXISTS who_api_key_credential (
       id       UUID         PRIMARY KEY,
       name     VARCHAR(255) NOT NULL,
       key_hash VARCHAR(64)  NOT NULL UNIQUE
   );
   ```

2. **Wiring the filter** — show how to add `ApiKeyAuthenticationFilter` to a
   security filter chain:
   ```java
   http.addFilterBefore(apiKeyAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
   ```

3. **Configuring the header name** — show the property with its default:
   ```yaml
   who:
     api-key:
       header-name: X-API-Key  # default
   ```

4. **Generating a key** — show a call to `ApiKeyService.create()`:
   ```java
   String rawKey = apiKeyService.create(identityId, "Production server");
   // Show rawKey to the user once — it cannot be retrieved again
   ```

5. **Using the key** — show the curl example:
   ```bash
   curl https://api.example.com/endpoint \
     -H "X-API-Key: who_<your-key>"
   ```

6. **Revoking a key** — explain that deleting the `who_api_key_credential` row
   by `id` immediately revokes access.

Also update the schema setup section (Option A / Flyway / Liquibase) to include
the `who-apikey` schema file alongside the existing module schemas.

## Acceptance criteria

- [ ] README has an **API Key Authentication** section covering all six points above
- [ ] The `spring.sql.init` schema-locations list in the README includes `classpath:org/jwcarman/who/apikey/schema.sql`
- [ ] Flyway and Liquibase examples in the README include the `who_api_key_credential` DDL
- [ ] `progress.txt` updated

## Implementation notes

- Documentation only — no code changes
- Keep examples minimal and copy-paste ready
