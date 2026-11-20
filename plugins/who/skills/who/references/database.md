# Database Setup

**Contents**

- How who migrates its tables
- Without Liquibase
- Running who's changelogs yourself
- Without `who-jdbc`
- Rebuilding the database on every start
- Rules for who's changelogs
- Liquibase extensions
- Libraries built on who
- Transactions and locks
- Passkey storage is PostgreSQL only

who keeps its data in PostgreSQL (14+) and creates and upgrades its own tables with Liquibase at
startup, through [migratory](https://jwcarman.github.io/migratory/). Add Spring Boot's Liquibase starter beside who:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-liquibase</artifactId>
</dependency>
```

Nothing else is needed for who's tables.

## How who migrates its tables

who's tables are created by [migratory](https://jwcarman.github.io/migratory/), which the starter
brings. Each who module that has tables declares its changelog in its jar's
`META-INF/migratory.properties`:

| Migration | Changelog | Runs after |
|---|---|---|
| `who-jdbc` | `classpath:org/jwcarman/who/jdbc/db/who-jdbc-changelog.xml` | |
| `who-rbac` | `classpath:org/jwcarman/who/rbac/db/who-rbac-changelog.xml` | `who-jdbc` |
| `who-federated` | `classpath:org/jwcarman/who/federated/db/who-federated-changelog.xml` | `who-jdbc` |
| `who-enrollment` | `classpath:org/jwcarman/who/enrollment/db/who-enrollment-changelog.xml` | `who-jdbc` |
| `who-apikey` | `classpath:org/jwcarman/who/apikey/db/who-apikey-changelog.xml` | `who-jdbc` |
| `who-email-link` | `classpath:org/jwcarman/who/emaillink/db/who-email-link-changelog.xml` | `who-jdbc` |
| `who-passkey` | `classpath:org/jwcarman/who/passkey/db/who-passkey-changelog.xml` | `who-jdbc` |

At startup migratory runs the changelog of each module on the classpath: `who-jdbc` first, then
the rest in name order. A module that is not on the classpath declares nothing. When a who release changes a table, it ships a new
changeset, and the next startup applies it.

migratory runs them with what Spring Boot's Liquibase uses for your own changelog: the same data
source (`@LiquibaseDataSource`, `spring.liquibase.url` and `user`, or the primary `DataSource`), the
same schemas, and the same `DATABASECHANGELOG` and `DATABASECHANGELOGLOCK` tables. Liquibase
identifies a changeset by its id, author and file path, so who's rows never collide with yours.

who's tables come first. Every `SpringLiquibase` bean waits for them, whether Spring Boot made it
or you declared your own, so your changesets can reference who's tables, for example with a
foreign key to `who_identity`. JPA and every bean that depends on database initialization wait
too. The run happens at startup even with `spring.main.lazy-initialization=true`, and in native
images. migratory's [guides](https://jwcarman.github.io/migratory/) have the details.

`spring.liquibase.enabled=false` turns off only Spring Boot's run of your changelog; who's tables
are still created. `migratory.enabled=false` turns who's run off (see
[below](#running-whos-changelogs-yourself)).

### Test slices

`@JdbcTest`, `@DataJdbcTest` and `@DataJpaTest` create who's tables before the application's
changelog runs, as an application does. who's tables are PostgreSQL only, so keep the slice on
PostgreSQL rather than the embedded database it uses by default:
`@AutoConfigureTestDatabase(replace = Replace.NONE)`, or a `@ServiceConnection` container.

### Your own changelog

Spring Boot's Liquibase needs a master changelog of your own, at
`db/changelog/db.changelog-master.yaml` unless `spring.liquibase.change-log` names another. An
application with no tables of its own still needs one; an empty one does:

```yaml
databaseChangeLog: []
```

Do not include who's changelogs from it; migratory runs them already.

## Without Liquibase

With who's modules on the classpath (the starter brings them) and no Liquibase, startup fails:

```text
migratory found migrations (who-jdbc, who-rbac, ...) but Liquibase is not on the classpath: add
spring-boot-starter-liquibase, or set migratory.enabled=false and include their changelogs yourself.
```

## Running who's changelogs yourself

Turn migratory's run off when the application runs its migrations in a way it cannot follow: a
schema per tenant, migrations run outside the application, or who's tables in a data source other
than the one Liquibase migrates:

```yaml
migratory:
  enabled: false
```

Then include the changelog of each who module you use from your own, `who-jdbc` first, before any
changeset that references who's tables:

```yaml
databaseChangeLog:
  - include:
      file: classpath:org/jwcarman/who/jdbc/db/who-jdbc-changelog.xml
  - include:
      file: classpath:org/jwcarman/who/rbac/db/who-rbac-changelog.xml
  # ...and the rest from the table above, for the modules on your classpath
```

A multi-tenant application includes them in each tenant's run. Adding a who module later means
adding its include too: nothing fails at startup without it, only the module's first query. `migratory.enabled=false` turns off
every library's migrations, not only who's, so include those libraries' changelogs too.

Including them records the same rows in `DATABASECHANGELOG` as migratory does, so an application
can move from one to the other without anything running twice, as long as both use the same
history table.

## Without `who-jdbc`

An application that keeps identities in a repository of its own, without `who-jdbc`, has no
`who_identity` table, and the other modules' tables reference it. Such an application sets
`migratory.enabled=false` and creates the tables it needs itself; otherwise the first module
changelog with a foreign key to `who_identity` fails startup.

## Rebuilding the database on every start

`spring.liquibase.drop-first` would drop the schema after who's tables were created, so migratory
fails startup when it is set. Set `migratory.drop-first=true` instead, in a development profile
only: migratory's run drops the default schema first, the application's tables and the history
table included, then builds who's tables, and Spring Boot's run builds the application's. See
migratory's
[Rebuilding the schema on every start](https://jwcarman.github.io/migratory/guides/changelog-rules/#rebuilding-the-schema-on-every-start).

## Rules for who's changelogs

An application can rely on these from one who release to the next:

- **A released changeset is never edited.** A change is a new changeset. Liquibase checks each
  changeset's checksum and fails startup when one changes, so do not edit who's either.
- **A changelog file never moves or is renamed.** Its path is part of each changeset's identity; a
  moved file would make Liquibase run its changesets again.
- **Changes only add:** new tables, new nullable columns, new indexes, unless a release's
  [upgrading notes](upgrading.md) say otherwise. An older instance still running during a rolling
  deploy keeps working.

## Liquibase extensions

Liquibase extensions on the classpath apply to who's run as they do to yours: Liquibase finds them
through its service loader. who adds none. For example, `liquibase-sessionlock` replaces the row in
`DATABASECHANGELOGLOCK` with a lock held by the database session, so an instance killed in the
middle of a migration does not leave the lock taken; with it on the classpath, who's run uses it
too.

## Libraries built on who

A library whose changesets reference who's tables declares its own migration to migratory, after
the who module whose tables it references:

```properties
# META-INF/migratory.properties in the library's jar
name=acme
changelog=classpath:com/acme/db/acme-changelog.xml
after=who-jdbc
```

migratory runs it after `who-jdbc` and before the application's changelog, on the same data source.
A library that references roles names `who-rbac` instead (`after=who-rbac`). See migratory's
[ordering guide](https://jwcarman.github.io/migratory/guides/ordering/).

## Transactions and locks

Three rules hold under concurrent requests only because who counts and changes in one database
transaction that holds row locks: the [last-credential rule](account.md#the-last-credential-rule)
locks the identity's `who_identity` row, the [last-admin rule](admin.md#the-last-admin) locks
`who_role` rows, and an [invitation](admin.md#invitations) creates everything in one transaction.
They need:

- **Exactly one `PlatformTransactionManager`**, which who uses for them. Spring Boot makes it for
  a single `DataSource`. With two or more, an application with `who-passkey`, `who-email-link`,
  `who-oidc` or `who-admin` fails startup; mark the one for who's `DataSource` `@Primary`.
- **who's `JdbcClient` on that manager's `DataSource`.** The locks are taken through who's
  `JdbcClient`; if its `DataSource` is not the one the transaction manager manages, each statement
  runs in a connection of its own, the lock is released at once, and two concurrent removals can
  both pass. Spring Boot's `JdbcClient` and transaction manager share the single `DataSource`, so
  only an application that defines its own `JdbcClient`, `DataSource` or transaction manager needs
  to check this.
- **`READ COMMITTED`.** The guards ask for it on their own transactions, whatever the database's
  default, so that a removal that waited for the lock counts what the first one committed. Run
  `LastCredentialGuard.removeIfNotLast` from your own code outside any transaction of yours:
  inside one, it joins your transaction at your transaction's isolation, and under
  `REPEATABLE READ` the count can be stale. The same holds for `LastAdminGuard`.

## Passkey storage is PostgreSQL only

The `who-passkey` repositories use `ON CONFLICT`, so passkey storage needs PostgreSQL 14+. The
tables are used only when `who-passkey` is on the classpath. See [Passkeys](passkeys.md).
