# who 0.10.0 Security Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the security review's findings in who (C1, C2, I1, I3–I6 and the minors) so 0.10.0 can be
released, with every fix proven by a test that failed first.

**Architecture:** A `Secrets` helper in who-core replaces UUIDs as the source of every secret. Enrollment
redemption becomes one conditional update that decides the winner before anything is linked. The API-key
filter leaves the servlet container and joins chains only through a `Customizer<HttpSecurity>`, and the
starter enables method security by default.

**Tech Stack:** Java 25, Spring Boot 4.0.3, Spring Security 7, JdbcClient, PostgreSQL 16 via Testcontainers
(the existing per-module `Abstract*Test` fixtures), JUnit 5, AssertJ, MockMvc.

**Spec:** `docs/superpowers/specs/2026-11-07-security-fixes-design.md`

## Global Constraints

- who targets JDK 25+; no compatibility work for older JDKs.
- Every new source file carries the Apache header (`Copyright © 2026 James Carman`); the license plugin
  checks it in the build.
- Format with Spotless (`./mvnw spotless:apply` before every test run; `check` runs in `validate`).
- Additions only to existing public APIs, except where the spec changes behaviour: enrollment token value
  format, API-key filter registration, method security on by default, exception messages.
- Secrets never appear in exception messages, `toString()` or logs.
- Identifiers stay UUIDv7 (`Identifiers.uuid()`); secrets come only from `Secrets.token()`.
- Repository and service tests use the module's existing `Abstract*Test` Postgres fixture.

## Review Focus

1. **Revoking a token that was already redeemed** must leave it `REDEEMED` with its `redeemedAt` (today
   `save(token.revoke())` overwrites the status). Test: Task 3 `revokingARedeemedTokenChangesNothing`.
2. **An application that already declares `@EnableMethodSecurity`** must start (no duplicate configuration)
   and keep enforcing. Test: Task 6 `appWithItsOwnMethodSecurityStillEnforces`.
3. **A JWT whose `sub` is present but blank** is 401, like a missing one. Test: Task 5
   `blankSubjectIsUnauthorized`.
4. **An API key sent with a whitespace-only header** is ignored, not looked up. Test: Task 4
   `blankHeaderIsIgnored`.
5. **A token value with surrounding whitespace or different case** does not redeem (values are exact).
   Test: Task 3 `tokenValueIsExact`.

---

### Task 1: `Secrets`

**Files:**
- Create: `who-core/src/main/java/org/jwcarman/who/core/crypto/Secrets.java`
- Modify: `who-core/src/main/java/org/jwcarman/who/core/id/Identifiers.java` (Javadoc only)
- Test: `who-core/src/test/java/org/jwcarman/who/core/crypto/SecretsTest.java`

**Interfaces:**
- Produces: `public final class Secrets { public static String token(); }`, which returns 32 bytes from one
  shared `private static final SecureRandom` (not `getInstanceStrong()`), encoded with
  `Base64.getUrlEncoder().withoutPadding()`, so 43 characters.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void tokenIs43UrlSafeCharacters() {
  assertThat(Secrets.token()).hasSize(43).matches("[A-Za-z0-9_-]{43}");
}

@Test
void tokensAreDistinct() {
  var seen = new HashSet<String>();
  for (int i = 0; i < 10_000; i++) {
    seen.add(Secrets.token());
  }
  assertThat(seen).hasSize(10_000);
}

@Test
void tokenDecodesToThirtyTwoBytes() {
  assertThat(Base64.getUrlDecoder().decode(Secrets.token())).hasSize(32);
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw -q spotless:apply && ./mvnw -q -pl who-core test -Dtest=SecretsTest`
Expected: compilation FAIL, no `Secrets`.

- [ ] **Step 3: Implement `Secrets.token()`;** add a sentence to `Identifiers`' Javadoc: identifiers are
  time-ordered and not secret; never use them as tokens (see `Secrets`).
- [ ] **Step 4: Run them to verify they pass.** Same command. Expected: PASS.
- [ ] **Step 5: Commit:** `feat(core): Secrets, the one source of secret tokens`.

### Task 2: Enrollment tokens: random values, masked, quiet

**Files:**
- Modify: `who-enrollment/src/main/java/org/jwcarman/who/enrollment/EnrollmentToken.java`
- Modify: `who-enrollment/src/main/java/org/jwcarman/who/enrollment/EnrollmentTokenNotFoundException.java`
- Modify: `who-enrollment/src/main/java/org/jwcarman/who/enrollment/WhoEnrollmentService.java` (the two
  `new EnrollmentTokenNotFoundException(...)` calls)
- Test: `who-enrollment/src/test/java/org/jwcarman/who/enrollment/EnrollmentTokenTest.java`

**Interfaces:**
- Consumes: `Secrets.token()` (Task 1).
- Produces:
  - `EnrollmentToken.create(identity, expiration)`: `value` is `Secrets.token()`;
  - `toString()` prints `value=***`;
  - `revoke()` keeps `redeemedAt`;
  - `EnrollmentTokenNotFoundException()` with no arguments, message `"Enrollment token not found"`. Both call
    sites use it.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void valueIsASecretNotAUuid() {
  var token = EnrollmentToken.create(identity(), Duration.ofHours(1));
  assertThat(token.value()).matches("[A-Za-z0-9_-]{43}");
  assertThatThrownBy(() -> UUID.fromString(token.value())).isInstanceOf(IllegalArgumentException.class);
}

@Test
void valueIsUnrelatedToTheId() {
  for (int i = 0; i < 1_000; i++) {
    var token = EnrollmentToken.create(identity(), Duration.ofHours(1));
    assertThat(token.value()).doesNotContain(token.id().toString());
  }
}

@Test
void toStringMasksTheValue() {
  var token = EnrollmentToken.create(identity(), Duration.ofHours(1));
  assertThat(token.toString()).doesNotContain(token.value()).contains("value=***");
}

@Test
void revokeKeepsRedeemedAt() {
  var redeemed = EnrollmentToken.create(identity(), Duration.ofHours(1)).redeem();
  assertThat(redeemed.revoke().redeemedAt()).isEqualTo(redeemed.redeemedAt());
}

@Test
void notFoundMessageCarriesNoToken() {
  assertThat(new EnrollmentTokenNotFoundException().getMessage()).isEqualTo("Enrollment token not found");
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw -q spotless:apply && ./mvnw -q -pl who-enrollment -am test -Dtest=EnrollmentTokenTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL: the value is a UUID; `toString` shows it; the no-argument constructor is missing.

- [ ] **Step 3: Implement.** Override `toString()` in the record. Remove the value-taking constructor of
  the exception, since nobody uses who yet.
- [ ] **Step 4: Run them to verify they pass,** then the module: `./mvnw -q -pl who-enrollment -am test`.
  Expected: PASS.
- [ ] **Step 5: Commit:** `fix(enrollment): random token values; no secrets in toString or messages`.

### Task 3: Atomic redemption; only active identities; already-linked credentials

**Files:**
- Modify: `who-enrollment/src/main/java/org/jwcarman/who/enrollment/EnrollmentTokenRepository.java`
- Modify: `who-enrollment/src/main/java/org/jwcarman/who/enrollment/JdbcEnrollmentTokenRepository.java`
- Modify: `who-enrollment/src/main/java/org/jwcarman/who/enrollment/WhoEnrollmentService.java`
- Create: `who-enrollment/src/main/java/org/jwcarman/who/enrollment/EnrollmentIdentityInactiveException.java`
- Create: `who-enrollment/src/main/java/org/jwcarman/who/enrollment/CredentialAlreadyLinkedException.java`
- Test: `who-enrollment/src/test/java/org/jwcarman/who/enrollment/WhoEnrollmentServiceTest.java` (Postgres) and
  `JdbcEnrollmentTokenRepositoryTest.java`

**Interfaces:**
- Produces:
  - `EnrollmentTokenRepository`:
    - `boolean redeem(UUID id, Instant now)`: the spec's conditional `UPDATE … WHERE id = :id AND status =
      'PENDING' AND expires_at > :now`, true when one row changed;
    - `boolean revoke(UUID id)`: `UPDATE … SET status = 'REVOKED' WHERE id = :id AND status = 'PENDING'`.
  - `EnrollmentIdentityInactiveException(UUID identityId)` extends `WhoException`:
    `"Identity %s is not active"`.
  - `CredentialAlreadyLinkedException(UUID credentialId)` extends `WhoException`:
    `"Credential %s is already linked to an identity"`.
  - `WhoEnrollmentService.enroll` (`@Transactional`), in order:
    1. find the token by hash, or throw `EnrollmentTokenNotFoundException()`;
    2. load the identity; if it is not `ACTIVE`, throw `EnrollmentIdentityInactiveException`;
    3. if `redeem(id, Instant.now())` is false, reread the token: expired → `EnrollmentTokenExpiredException`,
       otherwise `EnrollmentTokenNotPendingException`;
    4. link the credential, translating Spring's `DuplicateKeyException` into
       `CredentialAlreadyLinkedException`;
    5. return the identity.
  - `revokeToken` (`@Transactional`) uses `repository.revoke(id)`, and is a no-op for a token that isn't
    pending.

- [ ] **Step 1: Write the failing tests** in `WhoEnrollmentServiceTest` (Spring + Postgres):

```java
@Test
void concurrentRedemptionsLinkExactlyOneCredential() throws Exception {
  var identity = whoService.createIdentity();
  var token = enrollment.createToken(identity);
  var start = new CountDownLatch(1);
  Callable<Object> attempt =
      () -> {
        start.await();
        try {
          return enrollment.enroll(token.value(), credential());
        } catch (EnrollmentTokenNotPendingException e) {
          return e;
        }
      };
  try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
    var a = pool.submit(attempt);
    var b = pool.submit(attempt);
    start.countDown();
    var results = List.of(a.get(), b.get());
    assertThat(results).filteredOn(r -> r instanceof Identity).hasSize(1);
    assertThat(results).filteredOn(r -> r instanceof EnrollmentTokenNotPendingException).hasSize(1);
  }
  assertThat(jdbc.sql("select count(*) from who_credential_identity where identity_id = ?")
          .param(identity.id()).query(Integer.class).single())
      .isEqualTo(1);
}

@Test
void expiredTokenLinksNothing() {
  // a token saved with expiresAt in the past → EnrollmentTokenExpiredException, no link row
}

@Test
void suspendedIdentityIsRefused() {
  // identity saved SUSPENDED → EnrollmentIdentityInactiveException; token still PENDING; no link row
}

@Test
void alreadyLinkedCredentialIsAClearError() {
  // link credential C to identity X, then enroll(tokenFor(Y), C) → CredentialAlreadyLinkedException
}

@Test
void revokingARedeemedTokenChangesNothing() {
  // enroll, then revokeToken(token) → findToken shows REDEEMED with its redeemedAt
}

@Test
void tokenValueIsExact() {
  // enroll(" " + value, c) and enroll(value.toUpperCase(), c) → EnrollmentTokenNotFoundException
}
```

  In `JdbcEnrollmentTokenRepositoryTest`: `redeemIsTrueOnceThenFalse`, `redeemIsFalseWhenExpired` and
  `revokeOnlyAffectsPending`.
- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw -q spotless:apply && ./mvnw -q -pl who-enrollment -am verify`
Expected: FAIL. The concurrent test sees 2 links (or 2 identities); the suspended identity enrolls; the
duplicate is a `DuplicateKeyException`; the new methods don't exist.

- [ ] **Step 3: Implement** as in the Interfaces block. Don't remove `save`; creation still uses it.
- [ ] **Step 4: Run them to verify they pass.** Same command. Expected: PASS, and the concurrent test passes
  10 runs in a row (`-Dsurefire.rerunFailingTestsCount=0`, run in a loop).
- [ ] **Step 5: Commit:** `fix(enrollment): atomic single-use redemption; refuse inactive identities`.

### Task 4: API keys: `Secrets`, revoke, and the filter only inside a chain

**Files:**
- Modify: `who-apikey/src/main/java/org/jwcarman/who/apikey/ApiKeyService.java`
- Modify: `who-apikey/src/main/java/org/jwcarman/who/apikey/ApiKeyAuthenticationFilter.java`
- Create: `who-apikey/src/main/java/org/jwcarman/who/apikey/WhoApiKeySecurity.java`
- Modify: `who-autoconfigure/src/main/java/org/jwcarman/who/autoconfigure/WhoAutoConfiguration.java`
  (`ApiKeyConfiguration`)
- Test: `who-apikey/.../ApiKeyServiceTest.java`, `ApiKeyAuthenticationFilterTest.java`, new
  `ApiKeyChainTest.java`; `who-autoconfigure/.../WhoAutoConfigurationTest.java`

**Interfaces:**
- Consumes: `Secrets.token()` (Task 1).
- Produces:
  - `ApiKeyService.create` returns `"who_" + Secrets.token()`; the class has no `SecureRandom` of its own.
  - `ApiKeyService.revoke(UUID credentialId)` (`@Transactional`): deletes the credential row and
    `credentialIdentityRepository.unlink(id)`.
  - The filter sets an authentication only when `SecurityContextHolder.getContext().getAuthentication()` is
    null or anonymous.
  - `WhoApiKeySecurity`:
    - `static Customizer<HttpSecurity> apiKey()` resolves `ApiKeyAuthenticationFilter` from the
      `ApplicationContext` (`http.getSharedObject(ApplicationContext.class)`), then
      `http.addFilterBefore(filter, AuthorizationFilter.class)`;
    - `static Customizer<HttpSecurity> apiKey(ApiKeyAuthenticationFilter filter)` takes the filter instead.
  - Autoconfiguration adds `FilterRegistrationBean<ApiKeyAuthenticationFilter>` with `setEnabled(false)`.

- [ ] **Step 1: Write the failing tests:**
  - `ApiKeyServiceTest`:
    - `keyIsPrefixedSecret`: matches `who_[A-Za-z0-9_-]{43}`;
    - `revokeRemovesCredentialAndLink`: after `revoke`, the key no longer resolves; zero rows in both
      tables for that id.
  - `ApiKeyAuthenticationFilterTest`:
    - `keyDoesNotReplaceAnExistingAuthentication`: with a pre-set `TestingAuthenticationToken`, a valid key
      leaves it in place;
    - `blankHeaderIsIgnored`: header `"   "`, no repository call.
  - `ApiKeyChainTest`, a `@SpringBootTest` with MockMvc and two chains: `/keyed/**` with `apiKey()` and
    `/plain/**` without it, both `authenticated()`:
    - `validKeyAuthenticatesOnTheKeyedChain`: 200;
    - `keyIsIgnoredOnAChainWithoutIt`: 401.
  - `WhoAutoConfigurationTest`, a `WebApplicationContextRunner` with `who-apikey` on the classpath:
    `apiKeyFilterIsNotRegisteredWithTheServletContainer`. The `FilterRegistrationBean` for the filter
    exists, and `isEnabled()` is false.
- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw -q spotless:apply && ./mvnw -q -pl who-apikey,who-autoconfigure -am verify`
Expected: FAIL. The key is hex; `revoke` is missing; the filter replaces the authentication; there is no
registration bean.

- [ ] **Step 3: Implement** as in the Interfaces block.
- [ ] **Step 4: Run them to verify they pass.** Same command. Expected: PASS.
- [ ] **Step 5: Commit:** `fix(apikey): secrets from Secrets; filter only inside a chain; revoke`.

### Task 5: JWT: 401 for odd claims

**Files:**
- Modify: `who-jwt/src/main/java/org/jwcarman/who/jwt/WhoJwtAuthenticationConverter.java`
- Test: `who-jwt/src/test/java/org/jwcarman/who/jwt/WhoJwtAuthenticationConverterTest.java`, new
  `WhoJwtChainTest.java`

**Interfaces:**
- Produces: `convert(Jwt)` reads `getClaimAsString(JwtClaimNames.ISS)` and `…SUB`. If either is null or
  blank, it throws `BadCredentialsException("JWT is missing iss or sub")`. A non-URL issuer is looked up as
  is.

- [ ] **Step 1: Write the failing tests:**
  - Converter unit tests:
    - `missingIssuerIsBadCredentials`;
    - `blankSubjectIsUnauthorized`;
    - `nonUrlIssuerIsLookedUp`: a credential saved with issuer `"noir"` resolves.
  - `WhoJwtChainTest` (MockMvc, a resource-server chain using the converter): `missingIssuerIs401`, using
    `jwt().jwt(j -> j.claims(c -> c.remove("iss")))` from spring-security-test, expecting 401, not 500.
- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw -q spotless:apply && ./mvnw -q -pl who-jwt -am verify`
Expected: FAIL with `NullPointerException` / `IllegalArgumentException` (500).

- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them to verify they pass.** Same command. Expected: PASS.
- [ ] **Step 5: Commit:** `fix(jwt): missing or odd claims are 401, not 500`.

### Task 6: Method security on by default

**Files:**
- Modify: `who-autoconfigure/src/main/java/org/jwcarman/who/autoconfigure/WhoProperties.java`
  (`MethodSecurity { boolean enabled = true; }`)
- Modify: `who-autoconfigure/src/main/java/org/jwcarman/who/autoconfigure/WhoAutoConfiguration.java`
- Test: new `who-autoconfigure/src/test/java/org/jwcarman/who/autoconfigure/WhoMethodSecurityTest.java`

**Interfaces:**
- Produces: a nested `@Configuration(proxyBeanMethods = false) @EnableMethodSecurity` class with these
  conditions:
  - `@ConditionalOnClass(name = "org.jwcarman.who.spring.security.RequiresPermission")`;
  - `@ConditionalOnProperty(prefix = "who.method-security", name = "enabled", matchIfMissing = true)`;
  - `@ConditionalOnMissingBean(type = "org.springframework.security.config.annotation.method.configuration.PrePostMethodSecurityConfiguration")`.

- [ ] **Step 1: Write the failing tests** (`ApplicationContextRunner` with `WhoAutoConfiguration`, plus a
  test bean with a `@RequiresPermission("task.read")` method, called under
  `SecurityContextHolder` with and without the authority):
  - `requiresPermissionIsEnforcedWithoutEnableMethodSecurity`: without the authority, an
    `AccessDeniedException`;
  - `propertyFalseLeavesMethodSecurityOff`: `who.method-security.enabled=false`, so the call succeeds;
  - `appWithItsOwnMethodSecurityStillEnforces`: the user configuration has `@EnableMethodSecurity`; the
    context starts and the call is denied.
- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw -q spotless:apply && ./mvnw -q -pl who-autoconfigure -am test -Dtest=WhoMethodSecurityTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL: the first test's call succeeds, because there's no method security.

- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them to verify they pass,** then the module. Expected: PASS.
- [ ] **Step 5: Commit:** `fix(autoconfigure): enable method security by default (who.method-security.enabled)`.

### Task 7: RBAC: identity foreign key; resolver Javadoc

**Files:**
- Modify: `who-rbac/src/main/resources/org/jwcarman/who/rbac/schema.sql`
  (`who_identity_role.identity_id … REFERENCES who_identity(id) ON DELETE CASCADE`)
- Modify: `who-core/src/main/java/org/jwcarman/who/core/spi/PermissionsResolver.java` (Javadoc: one resolver)
- Test: `who-rbac/src/test/java/org/jwcarman/who/rbac/JdbcIdentityRoleRepositoryTest.java`

- [ ] **Step 1: Write the failing test** `deletingAnIdentityRemovesItsRoleAssignments`: assign a role, then
  `identityRepository.deleteById(identity.id())`, then `findRoleIdsByIdentityId` is empty. who-rbac's test
  schema must include who-jdbc's `schema.sql` first: check `who-rbac/src/test/resources/application.properties`.
- [ ] **Step 2: Run it to verify it fails.**
  `./mvnw -q spotless:apply && ./mvnw -q -pl who-rbac -am verify`. Expected: FAIL, the assignment is still
  there.
- [ ] **Step 3: Implement** the foreign key, and the Javadoc.
- [ ] **Step 4: Run it to verify it passes.** Expected: PASS.
- [ ] **Step 5: Commit:** `fix(rbac): role assignments go with their identity`.

### Task 8: README and example

**Files:**
- Modify: `README.md`, sections:
  - "Configure JWT": issuer validation with `issuer-uri`, or `JwtIssuerValidator` with `jwk-set-uri`;
  - "API Key Authentication" → "Wiring the filter": `http.with(WhoApiKeySecurity.apiKey(), withDefaults())`.
    The filter is not registered globally;
  - "Revoking a key": `apiKeyService.revoke(id)`;
  - "Enroll credentials": the credential comes from the authenticated redeemer. Persist the `JwtCredential`,
    then `enroll`; inactive identities are refused; mention `CredentialAlreadyLinkedException`;
  - "RBAC": method security is on by default, and `who.method-security.enabled`;
  - "Database setup": the Flyway and Liquibase copies of the rbac schema get the new foreign key;
  - "Configuration reference": add `who.method-security.enabled`.
- Modify: `who-example/src/main/resources/templates/index.html` (the token heading says "shown for this demo
  only; real apps never display tokens").
- Modify: who-example's security configuration, if it wires the API-key filter, to use the customizer.

- [ ] **Step 1: Update the README and the example** as listed.
- [ ] **Step 2: Run the whole build.** `./mvnw -q spotless:apply && ./mvnw -B verify`. Expected:
  BUILD SUCCESS; the existing tests plus the new ones all pass.
- [ ] **Step 3: Commit:** `docs: 0.10.0 security changes in the README and example`.
