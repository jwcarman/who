# Database Setup

**Contents**

- How who migrates its tables
- Without Liquibase
- Running who's changelog yourself
- `spring.liquibase.drop-first` is not supported
- Rules for who's changelogs
- Liquibase extensions
- Libraries built on who
- Transactions and locks
- Passkey storage is PostgreSQL only

who keeps its data in PostgreSQL (14+) and creates and upgrades its own tables with Liquibase at
startup. Add Spring Boot's Liquibase starter beside who:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-liquibase</artifactId>
</dependency>
```

Nothing else is needed for who's tables.

## How who migrates its tables

At startup, the `WhoLiquibase` bean runs who's changelog,
`classpath:org/jwcarman/who/db/who-changelog.xml`. It includes the changelog of each who module on
the classpath, `who-jdbc` first; modules that are not on the classpath are skipped. When a who
release changes a table, it ships a new changeset, and the next startup applies it.

who's run uses what Spring Boot's Liquibase uses for your own changelog:

- **The same data source.** A `DataSource` bean marked `@LiquibaseDataSource`; otherwise one built
  from `spring.liquibase.url`; otherwise the primary `DataSource`, with `spring.liquibase.user` and
  `password` when they are set; otherwise the primary `DataSource` as it is.
- **The same schemas.** `spring.liquibase.default-schema`, `liquibase-schema` and
  `liquibase-tablespace`.
- **The same history tables.** `DATABASECHANGELOG` and `DATABASECHANGELOGLOCK`, or the names in
  `spring.liquibase.database-change-log-table` and `database-change-log-lock-table`. Liquibase
  identifies a changeset by its id, author and file path, so who's rows never collide with yours.

who's run comes first. Every `SpringLiquibase` bean waits for it, whether Spring Boot made it or
you declared your own, so your changesets can reference who's tables, for example with a foreign
key to `who_identity`. JPA and every bean that depends on database initialization wait for it too.

With `spring.liquibase.enabled=false`, who's run is off as well: the application has turned
Liquibase off and migrates some other way.

### Your own changelog

Spring Boot's Liquibase needs a master changelog of your own, at
`db/changelog/db.changelog-master.yaml` unless `spring.liquibase.change-log` names another. An
application with no tables of its own still needs one; an empty one does:

```yaml
databaseChangeLog: []
```

Do not include `who-changelog.xml` from it; who runs it already.

## Without Liquibase

With `who-jdbc` on the classpath (the starter brings it) and no Liquibase, startup fails:

```text
Description:

who cannot start: who.migrations.enabled is true, but Liquibase is not on the classpath.

Action:

Add spring-boot-starter-liquibase, or set who.migrations.enabled=false and run classpath:org/jwcarman/who/db/who-changelog.xml yourself.
```

## Running who's changelog yourself

Turn who's run off when the application runs its migrations in a way who cannot follow: a schema
per tenant, migrations run outside the application, or who's tables in a data source other than
the one Liquibase migrates:

```yaml
who:
  migrations:
    enabled: false
```

Then include who's changelog from your own, before any changeset that references who's tables:

```yaml
databaseChangeLog:
  - include:
      file: classpath:org/jwcarman/who/db/who-changelog.xml
```

A multi-tenant application includes it in each tenant's run.

Including it records the same rows in `DATABASECHANGELOG` as `WhoLiquibase` does, so an
application can move from one to the other without anything running twice, as long as both use
the same history table.

The `classpath:` includes inside `who-changelog.xml` need Spring's Liquibase integration
(`SpringLiquibase`). The Liquibase CLI and the Liquibase Maven plugin may skip them without a
warning, so run the changelog through Spring.

## `spring.liquibase.drop-first` is not supported

`drop-first` drops the schema when Spring Boot's Liquibase runs, which is after who migrated it,
so who's tables are gone. An application that needs it turns who's run off and includes
`who-changelog.xml` itself, as above.

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

A library whose changesets reference who's tables runs its own changelog after who's by depending
on the `WhoLiquibase` bean. The bean is absent when the application turned who's run off, so take
it through an `ObjectProvider`:

```java
@Bean
AcmeLiquibase acmeLiquibase(ObjectProvider<WhoLiquibase> whoLiquibase, DataSource dataSource) {
    whoLiquibase.getIfAvailable(); // who's tables first
    return new AcmeLiquibase(dataSource);
}
```

Do not declare a `SpringLiquibase` bean for it: Spring Boot's Liquibase stands down when one
exists, and the application's own changelog stops running. Making the application's run wait for
the library's is the library's to do, the way who does it (a `DatabaseInitializerDetector` listed
in `spring.factories`).

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
