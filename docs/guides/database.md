# Database Setup

Who ships its own schema files on the classpath, one per module. The autoconfiguration runs them automatically — only modules present on the classpath contribute scripts, and all scripts use `CREATE TABLE IF NOT EXISTS` so they are safe to run on every startup.

## Auto-initialization

By default, schemas run automatically when the datasource is an embedded database (H2, HSQLDB, Derby). Control this with:

```yaml
who:
  initialize-schema: embedded  # always | embedded | never (default: embedded)
```

PostgreSQL is not an embedded database, so with the default the scripts do not run. Set `who.initialize-schema: always` for local development, or manage the schema with Flyway or Liquibase. For production, set `who.initialize-schema: never` and use Flyway or Liquibase.

The `who-rbac` schema requires the `who_identity` table from `who-jdbc` (`who_identity_role.identity_id` references it). Run the `who-jdbc` schema first. The Flyway and Liquibase copies below are already in that order.

## Flyway

Copy the following file to `src/main/resources/db/migration/V1__who.sql` in your application. If the application already has migrations, give the file the next free version number instead of `V1`:

```sql
CREATE TABLE IF NOT EXISTS who_identity (
    id         UUID PRIMARY KEY,
    status     VARCHAR(20)  NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS who_credential_identity (
    credential_id UUID PRIMARY KEY,
    identity_id   UUID NOT NULL,
    FOREIGN KEY (identity_id) REFERENCES who_identity(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS who_role (
    id   UUID        PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS who_role_permission (
    role_id    UUID         NOT NULL,
    permission VARCHAR(255) NOT NULL,
    PRIMARY KEY (role_id, permission),
    FOREIGN KEY (role_id) REFERENCES who_role(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS who_identity_role (
    identity_id UUID NOT NULL,
    role_id     UUID NOT NULL,
    PRIMARY KEY (identity_id, role_id),
    FOREIGN KEY (identity_id) REFERENCES who_identity(id) ON DELETE CASCADE,
    FOREIGN KEY (role_id) REFERENCES who_role(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS who_jwt_credential (
    id      UUID         PRIMARY KEY,
    issuer  VARCHAR(255) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    UNIQUE (issuer, subject)
);

CREATE TABLE IF NOT EXISTS who_enrollment_token (
    id          UUID         PRIMARY KEY,
    identity_id UUID         NOT NULL,
    token_value VARCHAR(255) NOT NULL UNIQUE,
    status      VARCHAR(20)  NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    redeemed_at TIMESTAMP WITH TIME ZONE,
    FOREIGN KEY (identity_id) REFERENCES who_identity(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS who_api_key_credential (
    id       UUID         PRIMARY KEY,
    name     VARCHAR(255) NOT NULL,
    key_hash VARCHAR(64)  NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS who_email_credential (
    id      UUID         PRIMARY KEY,
    address VARCHAR(320) NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS who_email_link_token (
    credential_id UUID                     PRIMARY KEY REFERENCES who_email_credential (id) ON DELETE CASCADE,
    token_hash    VARCHAR(64)              NOT NULL UNIQUE,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at    TIMESTAMP WITH TIME ZONE NOT NULL
);
```

## Liquibase

Every module ships a changelog that matches its `schema.sql`. `who-autoconfigure` ships `org/jwcarman/who/db/changelog-all.xml`, which includes the changelog of each module on the classpath, `who-jdbc` first. Include that one file from your master changelog:

```yaml
databaseChangeLog:
  - include:
      file: classpath:org/jwcarman/who/db/changelog-all.xml
```

Then set `who.initialize-schema: never`. Modules that are not on the classpath are skipped.

The `classpath:` includes inside `changelog-all.xml` need Spring Boot's Liquibase integration. The Liquibase CLI and the Liquibase Maven plugin may skip them without a warning, so run the changelog through Spring Boot.

The changelogs have no preconditions. Use them on a fresh schema. If the tables already exist because `schema.sql` created them, run Liquibase `changelog-sync` once before the first update, so Liquibase records the change sets as applied and does not try to create the tables again.
