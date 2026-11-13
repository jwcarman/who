# Contributing to who

Thanks for helping. who is a security library: it signs people in and decides what they may do.
That shapes how changes are made here, so please read this first.

**Found a vulnerability?** Don't open an issue or a pull request. Follow [SECURITY.md](SECURITY.md).

## Before you write code

- **Bugs:** open an issue with a way to reproduce it, ideally a failing test.
- **Features and behavior changes:** open an issue first and describe the problem, not just the
  change. Larger features get a short design document in `docs/superpowers/specs/` before code.
- Small fixes to docs and typos can go straight to a pull request.

## Building

You need Java 25 and Docker (the tests start PostgreSQL with Testcontainers).

```bash
./mvnw clean verify          # compile, test, and check formatting
./mvnw spotless:apply        # format the code
./mvnw clean verify -Pci     # with coverage, as CI runs it
./mvnw clean verify -Ppit    # mutation testing, now and then (not part of CI)
```

To work on the docs site, run `pip install --require-hashes -r docs/requirements.txt`, then
`mkdocs serve`. CI builds it with `mkdocs build --strict`, so broken links fail the build.

## What a pull request needs

- **Tests** for every behavior change, at the level a user sees it (an HTTP request and its
  response, a service call and its result). A security fix needs a test that fails without it.
- **No new warnings.** Fix the cause instead of suppressing it: no `@SuppressWarnings`,
  `// NOSONAR` or similar.
- **Explicit imports only**, no `import foo.*`, including static imports.
- **No fully qualified type names** in code; import the type.
- **Test names:** new test classes use snake_case method names with
  `@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)` and `@Nested` groups.
  Existing classes keep their own style.
- **Formatting:** `./mvnw spotless:apply` (Google Java Format).
- **Docs:** update the guide under `docs/guides/` that the change affects, and add a line under
  `## [Unreleased]` in [CHANGELOG.md](CHANGELOG.md). A breaking change also needs a section in
  `docs/guides/upgrading.md` with the steps to upgrade.
- **Schema changes:** a new Liquibase changeSet (never edit a released one), the same change in
  the module's `who-<module>-schema.sql`, and the SQL for Flyway and script users in the
  upgrading guide.
- **Commits:** a short, conventional summary such as `feat(email-link): ...`,
  `fix(passkey): ...`, `test: ...`, `docs: ...` or `build: ...`.

Sonar and CodeQL run on every pull request; please address what they find.

## Releases

Releases are cut by the maintainer from `main`. The version in the poms stays at the next
`-SNAPSHOT`; publishing a GitHub Release builds, signs and publishes to Maven Central.

## Conduct

Everyone taking part follows the [code of conduct](CODE_OF_CONDUCT.md).

## License

By contributing, you agree that your contributions are licensed under the
[Apache License 2.0](LICENSE).
