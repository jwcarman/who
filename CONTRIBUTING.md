# Contributing to who

Thanks for helping. who is a security library: it signs people in and decides what they may do.
That shapes how changes are made here, so please read this first.

**Found a vulnerability?** Don't open an issue or a pull request. Follow [SECURITY.md](SECURITY.md).

## Before you write code

- **Bugs:** open an issue with a way to reproduce it, ideally a failing test.
- **Features and behavior changes:** open an issue first and describe the problem, not just the
  change. Larger features get a short design document in `docs/superpowers/specs/` before code;
  see [ROADMAP.md](ROADMAP.md) for what is already planned.
- Small fixes to docs and typos can go straight to a pull request.

## Building

You need Java 25 and Docker (the tests start PostgreSQL with Testcontainers).

```bash
./mvnw clean verify          # compile, test, and check formatting
./mvnw spotless:apply        # format the code
./mvnw clean verify -Pci     # with coverage, as CI runs it
./mvnw clean verify -Ppit    # mutation testing, now and then (not part of CI)
```

### Fuzzing

The code that reads untrusted input (sign-in request bodies, typed codes, the API-key header) has
[Jazzer](https://github.com/CodeIntelligenceTesting/jazzer) fuzz tests, the `*FuzzTest` classes.
In a normal build each one replays its saved inputs as a regular test, which takes seconds. To
generate new inputs, run one fuzz test at a time with `JAZZER_FUZZ=1`:

```bash
./mvnw -q install -DskipTests
JAZZER_FUZZ=1 ./mvnw test -pl who-email-link -Dtest='SignInRequestFuzzTest#login_body_becomes_one_well_formed_proof'
```

It runs for the test's `maxDuration` (set `JAZZER_MAX_DURATION=10m` to change it) or until a
check fails. A failure writes the input to `src/test/resources/<package>/<Class>Inputs/<method>/`
as `crash-<hash>`: fix the bug and commit that file, so the input stays a regression test. The
generated corpus in `.cifuzz-corpus/` is not committed. The [Fuzz workflow](.github/workflows/fuzz.yml)
fuzzes every target for ten minutes each week.

A new fuzz test belongs with any new code that parses input from an unauthenticated caller. Keep
it fast and free of I/O, and assert what must hold for every input, not only that nothing throws.

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
`-SNAPSHOT`; publishing a GitHub Release builds, signs and publishes to Maven Central. Before a
release, the latest Fuzz workflow run should be green; start one by hand if the last is old.

## Conduct

Everyone taking part follows the [code of conduct](CODE_OF_CONDUCT.md).

## License

By contributing, you agree that your contributions are licensed under the
[Apache License 2.0](LICENSE).
