# who migrates its own tables with Liquibase

## Why

who's modules own tables (`who_identity`, `who_role`, the credential tables and so on). Today the
application has to get them into its database itself:

- **Liquibase:** the application includes `classpath:org/jwcarman/who/db/who-changelog.xml` from its
  own master changelog, in the right place, and sets `who.initialize-schema: never`.
- **Flyway:** the application copies a block of SQL from the database guide into its own
  `V<n>__who.sql`. When a who release changes a table, the application has to notice and write the
  migration itself.
- **Neither:** the bundled `schema.sql` scripts (`who.initialize-schema`) create missing tables. They
  cannot change a table that exists, so they only suit databases that start empty.

Every schema change is written in more than one place, and only the Liquibase path upgrades an
existing database properly.

roux, a library built on who, ships its own changelog whose changesets reference who's tables
(`who_role`, `who_role_permission`). It needs who's tables to exist before its own migrations run,
whatever order the application wrote its includes in. roux will run its own changelog the same way
this design runs who's.

## Goals

- With Liquibase on the classpath, who migrates its own tables at startup, before the application's
  migrations, with no setup.
- who's tables upgrade across who releases (new changesets, never edited ones).
- A library such as roux can order its own Liquibase run after who's, by depending on a bean of a
  type who names.
- An application can turn this off and run who's changelog itself (multi-tenant, or migrations
  outside the application).
- One place to write a schema change: a Liquibase changeset.

## Non-goals

- Flyway. who ships no Flyway migrations and no Flyway runner.
- Databases other than PostgreSQL. who is tested on PostgreSQL only.
- Migrating anything but who's own tables.
- Multi-tenant migration. An application with a schema per tenant turns who's runner off and runs
  `who-changelog.xml` in its own per-tenant runs.
- Backward compatibility. Existing databases set up by the scripts, by pasted SQL or by an older
  include are not a concern (owner and one friend use who); the docs say what to do, nothing in the
  code accommodates them.

## Anti-goals

- Silently stopping the application's own migrations. Declaring a `SpringLiquibase` bean does
  exactly that: Spring Boot's Liquibase configuration is
  `@ConditionalOnMissingBean(SpringLiquibase.class)` (checked in Boot 4.1.2).
- An application starting with no who tables and failing on its first query.
- Choosing the application's Liquibase extensions or settings for it.

## Design

### When the runner runs

| Situation | Result |
|---|---|
| Spring Boot's Liquibase support on the classpath (`spring-boot-liquibase` and `liquibase-core`, as `spring-boot-starter-liquibase` brings), `spring.liquibase.enabled` not `false`, `who.migrations.enabled` not `false` | `WhoLiquibase` runs who's changelog |
| `spring.liquibase.enabled=false` | nothing runs; the application has turned Liquibase off, so it migrates some other way (*my call*) |
| `who.migrations.enabled=false` (default `true`) | nothing runs; the application runs `who-changelog.xml` itself |
| Liquibase support missing, `who.migrations.enabled` not `false` | **startup fails** with a failure analysis: "who migrates its tables with Liquibase: add `spring-boot-starter-liquibase`, or set `who.migrations.enabled=false` and run `classpath:org/jwcarman/who/db/who-changelog.xml` yourself." |

The failure follows who's rule that a module on the classpath with missing required setup fails
startup rather than failing later. It applies only when `who-jdbc` is on the classpath (an
application with no who tables needs no Liquibase).

### `WhoLiquibase`

A bean of type `WhoLiquibase` (who's own type, not `SpringLiquibase`, so Boot's own Liquibase still
runs). On initialization it builds a `SpringLiquibase` internally and runs it, so Spring's resource
accessor resolves the `classpath:` includes. The `SpringLiquibase` is not exposed as a bean.

- **Changelog:** who's existing master, `classpath:org/jwcarman/who/db/who-changelog.xml`. It
  includes each module present, `who-jdbc` first.
- **History:** the application's `DATABASECHANGELOG` and `DATABASECHANGELOGLOCK` (or whatever
  `spring.liquibase.database-change-log-table` and `database-change-log-lock-table` name). Liquibase
  identifies a changeset by id, author and file path, so who's rows never collide with the
  application's, and each run ignores rows that are not its own.
- **Data source and schema:** the same Spring Boot's Liquibase would use for the application (the
  `@LiquibaseDataSource` or `spring.liquibase.url` when set, otherwise the primary `DataSource`; and
  `spring.liquibase.default-schema` and `liquibase-schema`), so who's tables land where the
  application's do. An application with several data sources whose who tables live elsewhere turns
  the runner off.
- **Extensions:** Liquibase extensions on the classpath, such as `liquibase-sessionlock`, apply to
  who's run as to the application's: Liquibase finds them through its service loader. who adds none.

### Ordering

- `WhoLiquibase` registers as a database initializer (a `DatabaseInitializerDetector` in
  `spring.factories`), so JPA and every bean that depends on database initialization wait for it.
- Every `SpringLiquibase` bean in the context (Boot's, or the application's own when Boot stood down
  because the application declared them) depends on `WhoLiquibase`, because the application's
  changesets may reference who's tables. Boot does not order one migration run after another on its
  own (*my call*: all of them, not only Boot's).
- A library orders its own run after who's by depending on `WhoLiquibase`. Making the application's
  run also wait for the library's is the library's to do, the same way who does (*my call*: who adds
  no shared hook until a second library needs one).
- **Wiping the schema after who** (`spring.liquibase.drop-first`) drops who's tables after who
  migrated them. Not supported: the database guide says so, and such an application turns the
  runner off and includes who's changelog itself (*my call*).

### Rules for who's changelogs from now on

- A released changeset is never edited; a change is a new changeset. Liquibase checks each
  changeset's checksum and fails startup when it changes.
- A changelog file never moves or is renamed: its path is part of each changeset's identity, and a
  moved file would make Liquibase run its changesets again.
- Changes only add (new tables, new nullable columns, new indexes) unless a release says otherwise,
  so an older instance still running during a rolling deploy keeps working.

A test pins every changelog's path, and checks that including `who-changelog.xml` from an
application's own master records the same paths as `WhoLiquibase` does, so an application can move
between the two without anything running twice.

### What goes away

- The `schema.sql` scripts in every module, `who.initialize-schema`,
  `WhoDataSourceScriptDatabaseInitializer`, and the scripts' runtime hints (`WhoSchemaRuntimeHints`
  and the `schema.sql` entries in each module's reachability metadata).
- H2. The `who-autoconfigure` tests that run on H2 today (about 20 classes) move to PostgreSQL
  through Testcontainers, sharing one container, as the module's other 24 database tests already do.
  Their tables come from `WhoLiquibase`, the same as in an application.
- The Flyway section of the database guide.

The examples and native tests add `spring-boot-starter-liquibase`. Their own tables (the examples'
`schema.sql`) move to a small changelog of their own, which runs after who's.

### Native images

Spring Boot's Liquibase support works in a native image. who's changelogs are already registered as
resources (the native tests run `who-changelog.xml` today); the builder checks every module's
changelog is covered.

## Approaches considered

1. **Liquibase only, failing startup without it (chosen).** One place to write a schema change, one
   runner, and a clear message instead of missing tables.
2. **Liquibase and Flyway, following the application's tool.** Flyway users get no setup too, but
   every change is written two or three times, and Flyway refuses a schema that already has tables
   but no Flyway history, so who would have to change the application's Flyway settings.
3. **who-jdbc depends on `liquibase-core` itself.** No setup for anyone, but a Flyway application
   meets the same non-empty-schema refusal, and who brings a migration tool the application did not
   choose.
4. **Keep the scripts beside Liquibase.** Applications with no migration tool would get tables, at
   the cost of writing every change twice; with who on PostgreSQL only and its own tests moving off
   H2, nothing needs them.

Precedent: Flowable and JobRunr migrate their own tables at startup; Django runs each app's
migrations in dependency order.

## Docs

- `docs/guides/database.md`: rewritten around Liquibase: who migrates itself, the failure without
  Liquibase, `who.migrations.enabled` and the manual include for multi-tenant and external
  migrations, the changelog rules an application can rely on, `drop-first` unsupported,
  `liquibase-sessionlock` as an optional extension that applies to who's run too. The Flyway section
  goes.
- `docs/guides/configuration.md`: `who.initialize-schema` goes, `who.migrations.enabled` arrives.
- `docs/guides/native.md`, `sessions.md`, `README.md`: references to the scripts and
  `who.initialize-schema` updated.
- `docs/guides/upgrading.md` and CHANGELOG (Breaking changes): who now requires Liquibase and
  migrates its own tables; `who.initialize-schema` and the scripts are gone; drop any include of
  `who-changelog.xml` (or set `who.migrations.enabled=false` and keep it), and start from a fresh
  database.

## Left to the builder

- The exact names of the property-reading helpers, the failure analyzer, and the
  `BeanFactoryPostProcessor` (or other mechanism) that adds the dependency to every `SpringLiquibase`
  bean.
- The shared PostgreSQL container setup for the moved `who-autoconfigure` tests.
- What happens to `FederatedCredentialUpgradeTest`, which tests an upgrade path from older
  databases (a non-goal now).
