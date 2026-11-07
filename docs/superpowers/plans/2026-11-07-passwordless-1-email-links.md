# Passwordless 1: Sessions and Email Links Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Browser sign-in by email link for who, through Spring Security's own one-time-token
login. The session principal is re-resolved on every request. All who tables move to
`TIMESTAMPTZ`, and every module ships a Liquibase changelog.

**Architecture:**
- `who-spring-security` gains the session pieces, all Spring-native:
  - a `SecurityContextRepository` that stores only ids and resolves a fresh `WhoPrincipal` on every
    request;
  - the `WhoWebSecurity.passwordless()` configurer, which sets CSRF, 401 instead of redirects,
    logout and the headers, and applies every `PasswordlessLoginMethod` bean;
  - `GET /who/me`.
- A new `who-email-link` module plugs into `oneTimeTokenLogin()` through its hooks:
  - a token service that only generates values;
  - JSON request parsing;
  - a provider that resolves through `WhoService`;
  - a success handler that answers `202` and does the real work (lookup, the upsert that is also
    the resend limit, sending) on another thread.

**Tech Stack:** Java 25, Spring Boot 4.1.2, Spring Security 7.1, JdbcClient on PostgreSQL 14+,
Jackson 3 (`tools.jackson`), Liquibase (test scope; apps bring their own), JUnit 5, Testcontainers
`postgres:16`, MockMvc.

**Spec:** `docs/superpowers/specs/2026-11-07-passwordless-credentials-design.md`. This plan covers
sections 2, 3, 5, 6, 7 and 8 ("plan 1"). Passkeys (section 4) and native support (section 9) are
plans 2 and 3.

## Global Constraints

- Work on branch `passwordless` in `~/IdeaProjects/who`. Build with `./mvnw`. Run
  `./mvnw -q spotless:apply` before every build. `./mvnw -B verify` must be green at every commit.
- Version stays `0.11.0-SNAPSHOT`. Java 25+. No new runtime dependency beyond those named here.
- Every token value comes from `Secrets.token()`. Only `MessageDigests.sha256Hex(value)` is stored.
- Spring-native: use Spring Security's endpoints (`/ott/generate`, `/login/ott`, `/logout`) and
  hooks. who's only endpoint of its own is `GET /who/me`.
- Tables are `who_`-prefixed. Timestamps are `TIMESTAMPTZ`, bound as `OffsetDateTime` in UTC, never
  `java.sql.Timestamp`.
- No `@SuppressWarnings` in `src/main`. No star imports. No fully qualified type names in code.
- Tests use Testcontainers `postgres:16`, following the module's `Abstract*Test` pattern (see
  `who-apikey/src/test/java/org/jwcarman/who/apikey/AbstractApiKeyTest.java`).
- Every new `src/main` file gets the Apache header (`./mvnw -Plicense license:format`).
- Commits end with the implementer's own `Co-Authored-By` trailer and
  `Claude-Session: https://claude.ai/code/session_014GnzDHDaZp6zmTz2XpjbJ3`. Never push.

## Review Focus

1. **A malformed body on `/ott/generate`** (bad JSON, no `email`, a non-string `email`, form
   encoding): still `202`, nothing sent, never a 500. Test: Task 6
   `malformedGenerateRequestsAnswer202AndSendNothing`.
2. **An address typed differently from how it was registered** (`"  Owner@Example.COM "`) finds
   `owner@example.com`. Test: Task 6 `addressIsMatchedAfterNormalizing`.
3. **A link used again after a successful sign-in** (the back button or a second tab) is `401`
   `invalid_token`. Test: Task 6 `usedLinkIsRejected`.
4. **A session whose email credential was revoked** is unauthenticated on its next request. Test:
   Task 6 `revokingTheCredentialEndsTheSession`.
5. **The mail server fails while sending:** the answer was already `202`, the failure is logged
   without the address, and the token is removed so the user can ask again at once. Test: Task 5
   `failedSendRemovesTheTokenSoTheUserCanRetry`.

---

### Task 1: `TIMESTAMPTZ` for existing tables

**Files:**
- Modify: `who-jdbc/src/main/resources/org/jwcarman/who/jdbc/schema.sql`,
  `who-jdbc/src/main/java/org/jwcarman/who/jdbc/JdbcIdentityRepository.java`
- Modify: `who-enrollment/src/main/resources/org/jwcarman/who/enrollment/schema.sql`,
  `who-enrollment/src/main/java/org/jwcarman/who/enrollment/JdbcEnrollmentTokenRepository.java`
- Test: `who-jdbc/src/test/java/org/jwcarman/who/jdbc/JdbcIdentityRepositoryTest.java`,
  `who-enrollment/src/test/java/org/jwcarman/who/enrollment/JdbcEnrollmentTokenRepositoryTest.java`

**Interfaces:**
- Produces: the convention later tasks follow. Write with `.param(name,
  instant.atOffset(ZoneOffset.UTC))`; read with `rs.getObject(col, OffsetDateTime.class).toInstant()`.

- [ ] **Step 1: Write the failing tests.** In each repository test:
  - `timestampColumnsAreTimestamptz`: query
    `information_schema.columns WHERE table_name = '<table>' AND column_name IN (...)`. Every
    `data_type` is `timestamp with time zone`.
  - `instantsRoundTripRegardlessOfJvmZone`: set `TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))`
    (restore in `finally`), save an entity with `Instant.parse("2026-01-15T12:34:56.123456Z")`, read
    it back, and assert `isEqualTo` the same instant.
- [ ] **Step 2: Run them to verify they fail.** `./mvnw -q -pl who-jdbc,who-enrollment -am verify`.
  Expected: FAIL. `data_type` is `timestamp without time zone`.
- [ ] **Step 3: Implement.** Change every `TIMESTAMP(9)` to `TIMESTAMPTZ`, and switch both repositories
  to the convention above. Remove the `java.sql.Timestamp` imports.
- [ ] **Step 4: Run them to verify they pass,** then `./mvnw -B verify`. Expected: all green.
- [ ] **Step 5: Commit:** `feat: TIMESTAMPTZ for every who timestamp`.

### Task 2: Session principal re-resolved on every request

**Files:**
- Modify: `who-spring-security/src/main/java/org/jwcarman/who/spring/security/WhoAuthenticationToken.java`
- Create: `who-spring-security/src/main/java/org/jwcarman/who/spring/security/WhoSession.java`
- Create: `who-spring-security/src/main/java/org/jwcarman/who/spring/security/WhoSessionSecurityContextRepository.java`
- Modify: `who-spring-security/pom.xml` (`jakarta.servlet:jakarta.servlet-api`, `provided`)
- Test: `who-spring-security/src/test/java/org/jwcarman/who/spring/security/WhoSessionSecurityContextRepositoryTest.java`

**Interfaces:**
- Consumes: `WhoService.resolve(Credential)` returning `Optional<WhoPrincipal>`; `Credential` has
  one method, `UUID id()`.
- Produces:
  - `WhoAuthenticationToken(WhoPrincipal principal, UUID credentialId)` (new constructor) and
    `Optional<UUID> credentialId()`. The existing one-argument constructor stays, with an empty
    credential id. `equals`/`hashCode` include it.
  - `record WhoSession(UUID identityId, UUID credentialId, Instant signedInAt) implements Serializable`.
  - `WhoSessionSecurityContextRepository(WhoService whoService, Clock clock) implements SecurityContextRepository`,
    with `public static final String SESSION_ATTRIBUTE = "org.jwcarman.who.session"` and
    `Optional<WhoSession> currentSession(HttpServletRequest)` (plan 2 reads `signedInAt`).

- [ ] **Step 1: Write the failing tests.** Use `MockHttpServletRequest`/`Response` and a Mockito
  `WhoService`.
  - `savingAWhoTokenStoresOnlyIds`: saving a context holding
    `new WhoAuthenticationToken(principal, credId)` puts exactly a `WhoSession(identityId, credId,
    clockInstant)` in the session. Nothing else from the principal is stored.
  - `loadingResolvesAFreshPrincipal`: the session holds a `WhoSession`, and `resolve` returns a
    principal with permissions `{"b"}`. The loaded context's authentication is a
    `WhoAuthenticationToken` with authorities `{"b"}` and the same credential id.
  - `inactiveOrUnlinkedEndsTheSession`: `resolve` returns empty, so the loaded context is empty and
    the session is invalidated.
  - `relinkedCredentialEndsTheSession`: `resolve` returns a principal for a different identity id,
    so the context is empty and the session is invalidated.
  - `nonWhoAuthenticationIsNotStored`: saving a `TestingAuthenticationToken` stores nothing.
  - `resavingKeepsSignedInAt`: saving again for the same credential keeps the original
    `signedInAt`.
- [ ] **Step 2: Run them to verify they fail.** `./mvnw -q -pl who-spring-security -am test`.
  Expected: compilation fails, because the types don't exist yet.
- [ ] **Step 3: Implement.** Implement `loadDeferredContext`, `saveContext` and `containsContext`.
  Loading happens lazily, inside the deferred supplier. Resolve with `whoService.resolve(() -> credentialId)`.
  `saveContext` with an empty or non-who authentication removes the attribute.
- [ ] **Step 4: Run them to verify they pass,** then `./mvnw -B verify`.
- [ ] **Step 5: Commit:** `feat(spring-security): session stores ids; principal re-resolved per request`.

### Task 3: `WhoWebSecurity.passwordless()` and `GET /who/me`

**Files:**
- Create: `who-spring-security/src/main/java/org/jwcarman/who/spring/security/PasswordlessLoginMethod.java`
- Create: `who-spring-security/src/main/java/org/jwcarman/who/spring/security/WhoWebSecurity.java`
- Create: `who-spring-security/src/main/java/org/jwcarman/who/spring/security/PasswordlessConfigurer.java`
- Create: `who-spring-security/src/main/java/org/jwcarman/who/spring/security/web/WhoMeController.java`
- Modify: `who-spring-security/pom.xml` (`spring-webmvc`, `optional`; test: `spring-boot-starter-web`)
- Test: `who-spring-security/src/test/java/org/jwcarman/who/spring/security/PasswordlessConfigurerTest.java`

**Interfaces:**
- Consumes: Task 2's `WhoSessionSecurityContextRepository`, found as a bean through
  `http.getSharedObject(ApplicationContext.class)`.
- Produces:
  - `interface PasswordlessLoginMethod { void configure(HttpSecurity http); }`, a bean a credential
    module contributes;
  - `WhoWebSecurity.passwordless()` returns `PasswordlessConfigurer extends AbstractHttpConfigurer<PasswordlessConfigurer, HttpSecurity>`;
  - `WhoMeController`: `GET /who/me` returns `record WhoMeResponse(UUID identityId, Set<String> permissions)`,
    or `401` when there is no `WhoPrincipal`.
- What the configurer does, all with Spring's own types:
  - **`init`:**
    - Get `http.getConfigurer(CsrfConfigurer.class)`. If it is `null`, throw
      `IllegalStateException("WhoWebSecurity.passwordless() requires CSRF protection; remove csrf.disable() from this chain")`.
      Otherwise set `CookieCsrfTokenRepository.withHttpOnlyFalse()` and `CsrfTokenRequestAttributeHandler`.
    - Set `securityContext(c -> c.securityContextRepository(repo))`.
    - Set `exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(UNAUTHORIZED)))`.
    - Set `logout(l -> l.logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(NO_CONTENT)).deleteCookies("XSRF-TOKEN"))`.
    - Set `headers(h -> h.referrerPolicy(r -> r.policy(NO_REFERRER)))`.
    - Call `configure(http)` on every `PasswordlessLoginMethod` bean, in `@Order` order.
  - **`configure`:** if `http.getSharedObject(SessionCreationPolicy.class) == STATELESS`, throw
    `IllegalStateException("WhoWebSecurity.passwordless() needs sessions; this chain is STATELESS")`.

- [ ] **Step 1: Write the failing tests.** Use a `@SpringBootTest` web app in test scope with a
  mocked `WhoService` bean, the repository bean, `WhoMeController`, and a chain built with
  `anyRequest().authenticated()` plus `http.with(WhoWebSecurity.passwordless(), withDefaults())`.
  Put a `WhoSession` into a `MockHttpSession` to stand for a signed-in user.
  - `unauthenticatedIs401NotARedirect`: `GET /who/me` returns `401` with no `Location` header.
  - `meReturnsTheFreshPrincipal`: the session plus `resolve` returning permissions `{"task.read"}`
    give `200` and `{"identityId": "<id>", "permissions": ["task.read"]}`.
  - `suspendedIdentityLosesAccessOnTheNextRequest`: the first request gives `200`. Then `resolve`
    returns empty, and the next request gives `401`.
  - `logoutRequiresCsrfAndAnswers204`: `POST /logout` without a token gives `403`. With
    `csrf().asHeader()` it gives `204` and the session is invalidated.
  - `csrfCookieIsReadableByScripts`: any response sets `XSRF-TOKEN` without `HttpOnly`.
  - `referrerPolicyIsNoReferrer`: the header is `no-referrer`.
  - `loginMethodBeansAreApplied`: a test `PasswordlessLoginMethod` bean that adds a marker filter
    gets applied.
  - `csrfDisabledChainFailsAtStartup` and `statelessChainFailsAtStartup`: use
    `ApplicationContextRunner` with each bad chain. `context.getStartupFailure()` has the exact
    message above in its cause chain.
- [ ] **Step 2: Run them to verify they fail.** `./mvnw -q -pl who-spring-security -am test`.
  Expected: compilation fails.
- [ ] **Step 3: Implement** the four files as described in the Interfaces block.
- [ ] **Step 4: Run them to verify they pass,** then `./mvnw -B verify`.
- [ ] **Step 5: Commit:** `feat(spring-security): WhoWebSecurity.passwordless() and GET /who/me`.

### Task 4: `who-email-link`: credentials and the token table

**Files:**
- Create the module `who-email-link`: `pom.xml` (who-core, who-spring-security,
  spring-boot-starter-security, spring-boot-starter-jdbc, spring-boot-starter-json; test: who-jdbc,
  Testcontainers, postgresql), and add it to the root `<modules>` after `who-apikey`.
- Create under `org/jwcarman/who/emaillink/`:
  - `EmailCredential.java`, `EmailCredentialRepository.java`, `JdbcEmailCredentialRepository.java`;
  - `EmailCredentialService.java`, `EmailAddressTakenException.java`;
  - `EmailLinkTokenRepository.java`, `JdbcEmailLinkTokenRepository.java`;
  - `src/main/resources/org/jwcarman/who/emaillink/schema.sql`.
- Test: `AbstractEmailLinkTest.java`, `EmailCredentialServiceTest.java`,
  `JdbcEmailLinkTokenRepositoryTest.java`, `EmailCredentialTest.java`, plus
  `src/test/resources/application.properties` (jdbc schema, then emaillink schema).

**Interfaces:**
- Consumes: `CredentialIdentityRepository.link/unlink`, `Credential`, Task 1's timestamp convention.
- Produces:
  - `record EmailCredential(UUID id, String address) implements Credential`, with
    `static EmailCredential create(String address)` (a new UUIDv7 id, normalized address) and
    `static String normalize(String address)` (trim, then `toLowerCase(Locale.ROOT)`; blank or
    `null` throws `IllegalArgumentException`).
  - `EmailCredentialRepository`: `EmailCredential save(EmailCredential)`,
    `Optional<EmailCredential> findByAddress(String normalized)`, `Optional<EmailCredential> findById(UUID)`,
    `boolean deleteById(UUID)`.
  - `EmailCredentialService(EmailCredentialRepository, CredentialIdentityRepository)`:
    - `EmailCredential register(Identity, String address)`, which throws `EmailAddressTakenException`.
      Its Javadoc states: call it only with an address the caller has verified.
    - `Optional<EmailCredential> findByAddress(String)` normalizes first.
    - `void revoke(UUID credentialId)` deletes the row and unlinks, in one `@Transactional` method.
  - `EmailLinkTokenRepository`:
    - `boolean issue(UUID credentialId, String tokenHash, Instant now, Instant expiresAt, Duration resendInterval)`
      is the spec's upsert. It returns `true` when a row changed.
    - `Optional<UUID> consume(String tokenHash, Instant now)` is the spec's
      `DELETE … RETURNING credential_id`.
    - `void delete(UUID credentialId)`.
- Schema, verbatim from the spec:
  - `who_email_credential(id UUID PRIMARY KEY, address VARCHAR(320) NOT NULL UNIQUE)`
  - `who_email_link_token(credential_id UUID PRIMARY KEY REFERENCES who_email_credential(id) ON DELETE CASCADE, token_hash VARCHAR(64) NOT NULL UNIQUE, created_at TIMESTAMPTZ NOT NULL, expires_at TIMESTAMPTZ NOT NULL)`

- [ ] **Step 1: Write the failing tests.**
  - `EmailCredentialTest`:
    - `normalizeTrimsAndLowerCases`: `"  Owner@Example.COM "` becomes `"owner@example.com"`;
    - `blankAddressIsRejected`.
  - `EmailCredentialServiceTest`:
    - `registerLinksTheCredentialToTheIdentity`;
    - `registeringATakenAddressThrows`, which also applies to the same address in different case;
    - `findByAddressNormalizes`;
    - `revokeDeletesTheCredentialAndItsLink`.
  - `JdbcEmailLinkTokenRepositoryTest`:
    - `firstIssueInserts`, which returns `true`;
    - `issueWithinTheResendIntervalChangesNothing`, which returns `false`, keeps the old hash, and
      the old token still consumes;
    - `issueAfterTheIntervalReplacesTheToken`, which returns `true`; the old hash no longer
      consumes and the new one does;
    - `consumeIsSingleUse`: the second consume is empty;
    - `expiredTokenDoesNotConsume`, using `now` after `expires_at`;
    - `concurrentConsumesOneWins`: two virtual threads consume one hash, and exactly one is present.
      Follow the concurrency pattern in `WhoEnrollmentServiceTest` (a `CountDownLatch` start).
    - `deletingTheCredentialDeletesItsToken`: the cascade.
- [ ] **Step 2: Run them to verify they fail.** `./mvnw -q -pl who-email-link -am verify`. Expected:
  compilation fails.
- [ ] **Step 3: Implement** the files above. All SQL uses JdbcClient named parameters.
- [ ] **Step 4: Run them to verify they pass,** then `./mvnw -B verify`.
- [ ] **Step 5: Commit:** `feat(email-link): email credentials and the single-row link token`.

### Task 5: `who-email-link`: the Spring one-time-token hooks

**Files** (all under `who-email-link/src/main/java/org/jwcarman/who/emaillink/`):
- Create:
  - `EmailLinkSettings.java`, `WhoOneTimeTokenService.java`;
  - `JsonGenerateOneTimeTokenRequestResolver.java`, `JsonOneTimeTokenAuthenticationConverter.java`;
  - `EmailLinkAuthenticationProvider.java`, `EmailLinkIssuer.java`, `EmailLinkGenerationSuccessHandler.java`;
  - `JsonAuthenticationHandlers.java`;
  - `EmailLinkSender.java`, `MailEmailLinkSender.java`;
  - `EmailLinkLoginMethod.java`.
- Modify: `who-email-link/pom.xml` (`spring-boot-starter-mail`, `optional`).
- Test: one unit test class per main class, same names plus `Test`, with Mockito and no database.

**Interfaces:**
- Consumes: Task 4's service and repositories; Task 2's `WhoAuthenticationToken(principal, credentialId)`;
  Task 3's `PasswordlessLoginMethod`; `WhoService.resolve`.
- Produces:
  - `record EmailLinkSettings(String linkBaseUrl, Duration tokenTtl, Duration resendInterval)`.
  - `WhoOneTimeTokenService(EmailLinkTokenRepository, Clock, Duration tokenTtl) implements OneTimeTokenService`:
    - `generate` returns `new DefaultOneTimeToken(Secrets.token(), request.getUsername(), now + ttl)`
      and touches no table;
    - `consume` hashes the value, calls `repository.consume`, and returns a `DefaultOneTimeToken`
      whose username is the credential id string, or `null` when empty.
  - `JsonGenerateOneTimeTokenRequestResolver(JsonMapper) implements GenerateOneTimeTokenRequestResolver`.
    It reads `{"email": "..."}`. Any malformed input gives `new GenerateOneTimeTokenRequest("")`,
    never an exception.
  - `JsonOneTimeTokenAuthenticationConverter(JsonMapper) implements AuthenticationConverter`. It
    reads `{"token": "..."}` and returns `OneTimeTokenAuthenticationToken.unauthenticated(token)`;
    malformed input gives `null`.
  - `EmailLinkAuthenticationProvider(OneTimeTokenService, WhoService) implements AuthenticationProvider`.
    It supports `OneTimeTokenAuthenticationToken`. It consumes the token, then resolves `() -> credentialId`.
    Success returns `new WhoAuthenticationToken(principal, credentialId)`. Every failure throws
    `InvalidOneTimeTokenException("invalid_token")`.
  - `EmailLinkIssuer(EmailCredentialService, WhoService, EmailLinkTokenRepository, EmailLinkSender, EmailLinkSettings, Clock)`,
    with `void issue(String address, String tokenValue)`:
    - a blank address, an unknown address, or one that doesn't resolve stops there;
    - otherwise it calls `repository.issue(...)`. If that returns `true`, it calls
      `sender.send(address, settings.linkBaseUrl() + "#token=" + tokenValue)`;
    - if `send` throws, it calls `repository.delete(credentialId)` and logs at WARN with the
      credential id, never the address.
  - `EmailLinkGenerationSuccessHandler(EmailLinkIssuer, Executor) implements OneTimeTokenGenerationSuccessHandler`.
    It sets status `202`, then calls `executor.execute(() -> issuer.issue(token.getUsername(), token.getTokenValue()))`.
  - `JsonAuthenticationHandlers(JsonMapper)` exposes `AuthenticationSuccessHandler success()`
    (`200`, `{"identityId": "..."}`) and `AuthenticationFailureHandler failure()` (`401`,
    `{"error": "invalid_token"}`).
  - `interface EmailLinkSender { void send(String address, String link); }`.
    `MailEmailLinkSender(JavaMailSender, String from, String subject)` sends plain text:
    `"Sign in: " + link`.
  - `EmailLinkLoginMethod(...all hooks...) implements PasswordlessLoginMethod`. It configures
    `http.oneTimeTokenLogin(o -> o.tokenService(..).generateRequestResolver(..).tokenGenerationSuccessHandler(..).authenticationConverter(..).authenticationProvider(..).authenticationSuccessHandler(..).authenticationFailureHandler(..).showDefaultSubmitPage(false))`
    and leaves the URLs at Spring's defaults.

- [ ] **Step 1: Write the failing tests:**
  - `WhoOneTimeTokenServiceTest`:
    - `generateTouchesNoTable` (verify no repository interaction; the value is 43 characters);
    - `consumeReturnsTheCredentialIdAsUsername`;
    - `consumeOfUnknownIsNull`;
    - `onlyTheHashReachesTheRepository`.
  - `JsonGenerateOneTimeTokenRequestResolverTest`: `readsEmail`, and
    `malformedBodiesGiveAnEmptyUsername` (parameterized over `"{"`, `"{}"`, `{"email": 5}`, and
    an empty body).
  - `JsonOneTimeTokenAuthenticationConverterTest`: `readsToken`, `malformedGivesNull`.
  - `EmailLinkAuthenticationProviderTest`:
    - `validTokenGivesWhoTokenWithCredentialId`;
    - `consumedOrUnknownTokenIsInvalid`;
    - `inactiveIdentityIsInvalid`.
  - `EmailLinkIssuerTest`:
    - `knownActiveAddressIsSentAFragmentLink` (the link equals `"https://app.test/signin#token=" + value`);
    - `unknownAddressSendsNothing`;
    - `inactiveIdentitySendsNothing`;
    - `withinResendIntervalSendsNothing`;
    - `failedSendRemovesTheTokenSoTheUserCanRetry`.
  - `EmailLinkGenerationSuccessHandlerTest`: `answers202BeforeTheWorkRuns`. The executor captures
    the `Runnable`; assert status `202` and no issuer call, then run it and assert the issuer call.
  - `JsonAuthenticationHandlersTest`: `successBody`, `failureBody`.
  - `MailEmailLinkSenderTest`: `sendsPlainTextFromAndSubject`.
- [ ] **Step 2: Run them to verify they fail.** `./mvnw -q -pl who-email-link -am test`.
  Expected: compilation fails.
- [ ] **Step 3: Implement** the files above.
- [ ] **Step 4: Run them to verify they pass,** then `./mvnw -B verify`.
- [ ] **Step 5: Commit:** `feat(email-link): Spring one-time-token hooks for email sign-in links`.

### Task 6: Autoconfiguration and the end-to-end sign-in flow

**Files:**
- Modify: `who-autoconfigure/src/main/java/org/jwcarman/who/autoconfigure/WhoAutoConfiguration.java`
  (new nested `PasswordlessConfiguration` and `EmailLinkConfiguration`), `WhoProperties.java`
  (`EmailLink`), `WhoDataSourceScriptDatabaseInitializer.java` (the emaillink schema, sentinel
  `org.jwcarman.who.emaillink.JdbcEmailCredentialRepository`, after apikey), and `who-autoconfigure/pom.xml`
  (who-email-link `optional`).
- Modify: `who-spring-boot-starter/pom.xml` (add who-email-link).
- Test: `who-autoconfigure/src/test/java/org/jwcarman/who/autoconfigure/EmailLinkSignInTest.java`
  (`@SpringBootTest` with MockMvc on Postgres; the test app's chain applies `passwordless()`).

**Interfaces:**
- Consumes: Tasks 2–5.
- Produces:
  - **Properties:**
    - `who.email-link.link-base-url`: no default; required when who-email-link is present, and
      startup fails naming the property if it is missing;
    - `who.email-link.token-ttl`: `5m`;
    - `who.email-link.resend-interval`: `60s`;
    - `who.email-link.from`;
    - `who.email-link.subject`: `"Your sign-in link"`.
  - **Beans, each `@ConditionalOnMissingBean`:**
    - `Clock whoClock` (`Clock.systemUTC()`);
    - `WhoSessionSecurityContextRepository`;
    - `WhoMeController` (`@ConditionalOnWebApplication(type = SERVLET)`);
    - every Task 4 and Task 5 class;
    - `Executor whoEmailLinkExecutor`, a `SimpleAsyncTaskExecutor("who-email-link-")` with
      `setVirtualThreads(true)`;
    - `MailEmailLinkSender`, only `@ConditionalOnBean(JavaMailSender.class)`;
    - `EmailLinkLoginMethod`.

- [ ] **Step 1: Write the failing tests.** The test app replaces `EmailLinkSender` with a recorder,
  and `whoEmailLinkExecutor` with `Runnable::run` so assertions are deterministic. Seed an `ACTIVE`
  identity with the email `owner@example.com` and a permission `task.read` (through a stub
  `PermissionsResolver` bean).
  - `emailLinkSignInEndToEnd`:
    1. `POST /ott/generate` `{"email":"owner@example.com"}` with CSRF gives `202`, and one link is
       recorded.
    2. `POST /login/ott` `{"token": <from the link's fragment>}` with CSRF gives `200`
       `{"identityId": ...}`, the session id changed, and the `XSRF-TOKEN` cookie value
       changed, because Spring rotates CSRF at sign-in.
    3. `GET /who/me` with that session gives `200`, with permissions `["task.read"]`.
  - `unknownAddressGetsTheSame202AndNoEmail`.
  - `secondRequestWithinTheIntervalSendsNoEmail`.
  - `newLinkReplacesTheOldOne`: with the clock moved past the interval, the old token gives `401`
    and the new one gives `200`.
  - `usedLinkIsRejected`: the second `POST /login/ott` with the same token gives `401`
    `{"error": "invalid_token"}`.
  - `expiredLinkIsRejected`.
  - `suspendedIdentityCannotSignIn`, plus `suspendingEndsAnExistingSession`.
  - `revokingTheCredentialEndsTheSession`.
  - `signInWithoutCsrfIs403`, for both endpoints.
  - `addressIsMatchedAfterNormalizing`.
  - `malformedGenerateRequestsAnswer202AndSendNothing`.
  - `concurrentRequestsSendOneEmail`: two threads post for the same address against two
    `MockMvc`s built on two application contexts sharing one database. Exactly one link is
    recorded.
  - `missingLinkBaseUrlFailsStartup`, with `ApplicationContextRunner`.
- [ ] **Step 2: Run them to verify they fail.** `./mvnw -q -pl who-autoconfigure -am verify`.
  Expected: FAIL. There is no `oneTimeTokenLogin`, so `/ott/generate` gives `401`.
- [ ] **Step 3: Implement** the autoconfiguration and the properties.
- [ ] **Step 4: Run them to verify they pass,** then `./mvnw -B verify`.
- [ ] **Step 5: Commit:** `feat(autoconfigure): email-link sign-in and passwordless sessions`.

### Task 7: Liquibase changelogs

**Files:**
- Create `db/changelog.xml` next to each `schema.sql`:
  `who-jdbc/src/main/resources/org/jwcarman/who/jdbc/db/changelog.xml`, and the same path under
  rbac, jwt, enrollment, apikey and emaillink.
- Create: `who-autoconfigure/src/main/resources/org/jwcarman/who/db/changelog-all.xml`.
- Modify: `who-autoconfigure/pom.xml` (test: `org.liquibase:liquibase-core`, version managed by Boot).
- Test: `who-autoconfigure/src/test/java/org/jwcarman/who/autoconfigure/LiquibaseChangelogTest.java`

**Interfaces:**
- Produces:
  - `changelog-all.xml` includes the six module changelogs, in the order jdbc, rbac, jwt,
    enrollment, apikey, emaillink. Each include is `<include file="classpath:org/jwcarman/who/<module>/db/changelog.xml" errorIfMissing="false"/>`.
  - Changeset ids are `who-<table>-1`, author `who`.
  - Liquibase types: `uuid`, `varchar(n)`, `TIMESTAMP WITH TIME ZONE`. The changelogs must produce
    exactly the tables, columns, keys and cascades of `schema.sql`.

- [ ] **Step 1: Write the failing test.** `changelogsMatchSchemaSql`:
  - create two databases on one container;
  - apply `changelog-all.xml` to the first with `liquibase.command.CommandScope("update")`;
  - apply every `schema.sql` (in `WhoDataSourceScriptDatabaseInitializer`'s order) to the second;
  - for both, collect the sorted rows of `information_schema.columns` (table, column, data_type,
    is_nullable) and `information_schema.table_constraints` joined with `key_column_usage` (table,
    constraint_type, column), ignoring Liquibase's own tables and constraint names;
  - assert both sets are equal.

  Also add `changelogAllSkipsAbsentModules`: with a class loader that hides the emaillink
  changelog, `update` still succeeds.
- [ ] **Step 2: Run it to verify it fails.** Expected: FAIL, because `changelog-all.xml` is not found.
- [ ] **Step 3: Implement** the seven XML files.
- [ ] **Step 4: Run it to verify it passes,** then `./mvnw -B verify`.
- [ ] **Step 5: Commit:** `feat: Liquibase changelogs for every module, with changelog-all.xml`.

### Task 8: Documentation

**Files:**
- Create: `docs/guides/email-links.md`.
- Modify:
  - `docs/guides/database.md`: Liquibase becomes "include `changelog-all.xml`"; the Flyway copy
    gets `TIMESTAMPTZ` and the two new tables;
  - `docs/guides/configuration.md`: the five `who.email-link.*` properties;
  - `docs/guides/upgrading.md`: a new "Upgrading to 0.11.0" section at the top;
  - `docs/index.md` and `README.md`: who-email-link in the modules tables;
  - `mkdocs.yml`: an "Email Links" guide after "API Keys", and the upgrading nav title becomes
    "Upgrading";
  - `CHANGELOG.md`: entries under `[Unreleased]`.

- [ ] **Step 1: Write `email-links.md`.** It covers:
  - the chain, with `http.with(WhoWebSecurity.passwordless(), Customizer.withDefaults())`, and the
    rule that stateless `/api/**` callers go in a separate chain;
  - the SPA flow: get the CSRF cookie, then `POST /ott/generate`, then the user's page reads
    `#token=` and posts `/login/ott`, then `/who/me` and `/logout`;
  - `register` must be called only with verified addresses;
  - the resend interval;
  - **"Rate limiting at the ingress"**, with an nginx `limit_req` example for `/ott/generate` and
    `/login/ott`;
  - the mailbox as the root of trust, and admin recovery (`revoke`, then `register`);
  - one tenant per JVM;
  - the session cookie properties (`server.servlet.session.cookie.same-site=lax`, `secure=true`).
- [ ] **Step 2: Write the upgrade section:**
  - the `ALTER TABLE … TYPE timestamptz USING <col> AT TIME ZONE '<zone>'` statements for
    `who_identity.created_at/updated_at` and `who_enrollment_token.created_at/expires_at/redeemed_at`,
    with a note to use the zone the JVM ran in;
  - Liquibase users switch to `changelog-all.xml` and must not re-run it over tables created by
    hand.
- [ ] **Step 3: Verify.** `mkdocs build --strict`
  (`pip install --only-binary ':all:' mkdocs-material==9.7.7`). Expected: no warnings. Then
  `./mvnw -B verify`.
- [ ] **Step 4: Commit:** `docs: email-link sign-in, Liquibase changelog-all, upgrading to 0.11.0`.
