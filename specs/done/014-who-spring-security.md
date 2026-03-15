# who-spring-security: shared Spring Security types

## What to build

Create a new `who-spring-security` Maven module to hold Spring Security integration
types that are shared across credential modules. The immediate motivation is
eliminating the duplicate `WhoAuthenticationToken` that currently exists in both
`who-jwt` and `who-apikey`.

### Module setup

New Maven module `who-spring-security` with dependencies:
- `who-core`
- `spring-boot-starter-security`

Add to root `pom.xml` `<modules>` list — must appear **before** `who-jwt` and
`who-apikey` so Maven builds it first.

Add as a dependency in:
- `who-jwt/pom.xml`
- `who-apikey/pom.xml`
- `who-autoconfigure/pom.xml` (optional, already transitively available but make it explicit)

### WhoAuthenticationToken

Move `WhoAuthenticationToken` from `who-jwt` into `who-spring-security`:

```java
package org.jwcarman.who.spring.security;
```

The class itself is unchanged — just the package. Keep the existing JavaDoc.

### Cleanup

- Delete `who-jwt/src/main/java/org/jwcarman/who/jwt/WhoAuthenticationToken.java`
- Delete `who-apikey/src/main/java/org/jwcarman/who/apikey/WhoAuthenticationToken.java`
- Update all import statements in `who-jwt` and `who-apikey` that reference the old classes
- Update any tests that import the old classes

## Acceptance criteria

- [ ] `who-spring-security` module exists and builds successfully
- [ ] `WhoAuthenticationToken` lives in `org.jwcarman.who.spring.security` — not in `who-jwt` or `who-apikey`
- [ ] No `WhoAuthenticationToken` class remains in `who-jwt` or `who-apikey`
- [ ] `who-jwt` and `who-apikey` both compile against the shared class
- [ ] `who-spring-security` added to root `pom.xml` modules before `who-jwt` and `who-apikey`
- [ ] `who-spring-security` added to `who-spring-boot-starter` dependencies
- [ ] `mvn test` passes (all modules)
- [ ] `mvn -P license verify` passes
- [ ] `WhoAuthenticationToken` JavaDoc is present
- [ ] `progress.txt` updated

## Implementation notes

- No behavior change — this is purely structural
- `who-core` must remain Spring-free; `who-spring-security` is the right home for any
  shared type that has a Spring Security dependency
- Do not add anything else to `who-spring-security` beyond what is described here
