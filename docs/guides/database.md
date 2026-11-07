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
    created_at TIMESTAMP(9) NOT NULL,
    updated_at TIMESTAMP(9) NOT NULL
);

CREATE TABLE IF NOT EXISTS who_credential_identity (
    credential_id UUID PRIMARY KEY,
    identity_id   UUID NOT NULL,
    FOREIGN KEY (identity_id) REFERENCES who_identity(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS who_role (
    id   UUID         PRIMARY KEY,
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

CREATE TABLE IF NOT EXISTS who_api_key_credential (
    id       UUID         PRIMARY KEY,
    name     VARCHAR(255) NOT NULL,
    key_hash VARCHAR(64)  NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS who_enrollment_token (
    id          UUID         PRIMARY KEY,
    identity_id UUID         NOT NULL,
    token_value VARCHAR(255) NOT NULL UNIQUE,
    status      VARCHAR(20)  NOT NULL,
    created_at  TIMESTAMP(9) NOT NULL,
    expires_at  TIMESTAMP(9) NOT NULL,
    redeemed_at TIMESTAMP(9),
    FOREIGN KEY (identity_id) REFERENCES who_identity(id) ON DELETE CASCADE
);
```

## Liquibase

Copy the following file to `src/main/resources/db/changelog/001-who.yaml` in your application:

```yaml
databaseChangeLog:
  - changeSet:
      id: who-identity
      author: who
      changes:
        - createTable:
            tableName: who_identity
            columns:
              - column:
                  name: id
                  type: uuid
                  constraints:
                    primaryKey: true
              - column:
                  name: status
                  type: varchar(20)
                  constraints:
                    nullable: false
              - column:
                  name: created_at
                  type: timestamp
                  constraints:
                    nullable: false
              - column:
                  name: updated_at
                  type: timestamp
                  constraints:
                    nullable: false

  - changeSet:
      id: who-credential-identity
      author: who
      changes:
        - createTable:
            tableName: who_credential_identity
            columns:
              - column:
                  name: credential_id
                  type: uuid
                  constraints:
                    primaryKey: true
              - column:
                  name: identity_id
                  type: uuid
                  constraints:
                    nullable: false
                    foreignKeyName: fk_credential_identity_identity
                    references: who_identity(id)
                    deleteCascade: true

  - changeSet:
      id: who-role
      author: who
      changes:
        - createTable:
            tableName: who_role
            columns:
              - column:
                  name: id
                  type: uuid
                  constraints:
                    primaryKey: true
              - column:
                  name: name
                  type: varchar(255)
                  constraints:
                    nullable: false
                    unique: true

  - changeSet:
      id: who-role-permission
      author: who
      changes:
        - createTable:
            tableName: who_role_permission
            columns:
              - column:
                  name: role_id
                  type: uuid
                  constraints:
                    nullable: false
                    foreignKeyName: fk_role_permission_role
                    references: who_role(id)
                    deleteCascade: true
              - column:
                  name: permission
                  type: varchar(255)
                  constraints:
                    nullable: false
        - addPrimaryKey:
            tableName: who_role_permission
            columnNames: role_id, permission

  - changeSet:
      id: who-identity-role
      author: who
      changes:
        - createTable:
            tableName: who_identity_role
            columns:
              - column:
                  name: identity_id
                  type: uuid
                  constraints:
                    nullable: false
                    foreignKeyName: fk_identity_role_identity
                    references: who_identity(id)
                    deleteCascade: true
              - column:
                  name: role_id
                  type: uuid
                  constraints:
                    nullable: false
                    foreignKeyName: fk_identity_role_role
                    references: who_role(id)
                    deleteCascade: true
        - addPrimaryKey:
            tableName: who_identity_role
            columnNames: identity_id, role_id

  - changeSet:
      id: who-jwt-credential
      author: who
      changes:
        - createTable:
            tableName: who_jwt_credential
            columns:
              - column:
                  name: id
                  type: uuid
                  constraints:
                    primaryKey: true
              - column:
                  name: issuer
                  type: varchar(255)
                  constraints:
                    nullable: false
              - column:
                  name: subject
                  type: varchar(255)
                  constraints:
                    nullable: false
        - addUniqueConstraint:
            tableName: who_jwt_credential
            columnNames: issuer, subject

  - changeSet:
      id: who-api-key-credential
      author: who
      changes:
        - createTable:
            tableName: who_api_key_credential
            columns:
              - column:
                  name: id
                  type: uuid
                  constraints:
                    primaryKey: true
              - column:
                  name: name
                  type: varchar(255)
                  constraints:
                    nullable: false
              - column:
                  name: key_hash
                  type: varchar(64)
                  constraints:
                    nullable: false
                    unique: true

  - changeSet:
      id: who-enrollment-token
      author: who
      changes:
        - createTable:
            tableName: who_enrollment_token
            columns:
              - column:
                  name: id
                  type: uuid
                  constraints:
                    primaryKey: true
              - column:
                  name: identity_id
                  type: uuid
                  constraints:
                    nullable: false
                    foreignKeyName: fk_enrollment_token_identity
                    references: who_identity(id)
                    deleteCascade: true
              - column:
                  name: token_value
                  type: varchar(255)
                  constraints:
                    nullable: false
                    unique: true
              - column:
                  name: status
                  type: varchar(20)
                  constraints:
                    nullable: false
              - column:
                  name: created_at
                  type: timestamp
                  constraints:
                    nullable: false
              - column:
                  name: expires_at
                  type: timestamp
                  constraints:
                    nullable: false
              - column:
                  name: redeemed_at
                  type: timestamp
```
