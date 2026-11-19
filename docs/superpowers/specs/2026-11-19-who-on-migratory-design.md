# who on migratory

## Intent

who migrates its own tables before the application's (the who-migrations design). migratory now
does that job for any library: a library declares its changelog in `META-INF/migratory.properties`
and migratory runs every declared changelog in dependency order, before the application's
Liquibase. who moves onto it so who, roux, and any later library share one runner and one ordering
mechanism instead of who's private `INITIALIZER_ORDER` hook.

## Goals

- Each who module jar that owns tables declares its own migration, at its existing changelog path.
- who's runner, detector, migrations auto-configuration, master changelog, ordering hook and
  `who.migrations.enabled` go away; migratory's equivalents replace them.
- Databases migrated by who's runner, or by an application including who's changelogs, run nothing
  again (Liquibase records the same per-module paths).

## Non-goals

- Changing any changeset.
- roux's move (its own plan; its session has been told).
- Publishing migratory (who builds against its local `0.1.0-SNAPSHOT`; who's CI stays red until
  migratory is released).

## Approaches

1. **One declaration per module** (picked): `name=who-<module>`, the module's changelog,
   `after=who-jdbc` for every module but who-jdbc. A module absent from the class path simply
   declares nothing, so the master changelog's `errorIfMissing="false"` trick is unnecessary.
2. One `who` declaration naming the master changelog. Keeps the master file but hides who's
   modules from other libraries' `after`, so roux could not order after `who-rbac` alone.
3. Keep who's runner and have it register with migratory. Two runners; no.

## Decisions

- Declaration names: `who-jdbc`, `who-rbac`, `who-federated`, `who-enrollment`, `who-apikey`,
  `who-email-link`, `who-passkey`. Every changelog references only `who_identity` (who-jdbc), so
  each non-jdbc module has `after=who-jdbc`.
- `who-autoconfigure` depends on `migratory-autoconfigure` (compile scope); Liquibase stays
  optional, and migratory's own check fails startup when migrations are declared without it.
- The master `who-changelog.xml` is removed (breaking changes are fine). An application that turns
  migratory off includes the module changelogs itself.
- Behaviour change: `spring.liquibase.enabled=false` no longer stops who's tables; migratory runs
  regardless (its documented behaviour). `migratory.enabled=false` is the switch.
- who's native hint for the master changelog goes; migratory's AOT registers each changelog's
  directory.

## Left to the builder

Which who-autoconfigure tests survive: those that pin who's behaviour (tables before the
application's, the pinned paths, a manual include reruns nothing, slices, the upgrade tests) stay
and run through migratory; those that re-test runner mechanics migratory already tests (data source
choice, runner settings, lazy init, missing Liquibase) go.
