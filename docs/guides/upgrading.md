# Upgrading

## Upgrading to 0.19.0

0.19.0 adds [`@RequiresRecentSignIn`](security-model.md#recent-sign-in), lets people
[see and end their sessions](sessions.md#see-and-end-sessions), and moves every who endpoint
under one root.

### Every endpoint under one root

Update the redirect URI registered at each provider to
`https://<your-host>/who/oidc/callback/<registration-id>`.

Every who endpoint now lives under `who.base-path`, `/who` unless you set it. Change your
front end's calls, and any `requestMatchers` or proxy rules that name them:

| Before | From 0.19.0 |
|---|---|
| `POST /ott/generate` | `POST /who/email/send` |
| `POST /login/ott` | `POST /who/email/sign-in` |
| `POST /logout` | `POST /who/logout` |
| `POST /webauthn/register/options` | `POST /who/passkey/register/options` |
| `POST /webauthn/register` | `POST /who/passkey/register` |
| `DELETE /webauthn/register/{id}` | `DELETE /who/passkey/register/{id}` |
| `POST /webauthn/authenticate/options` | `POST /who/passkey/sign-in/options` |
| `POST /login/webauthn` | `POST /who/passkey/sign-in` |
| `GET /oauth2/authorization/{id}` | `GET /who/oidc/authorize/{id}` |
| `GET /login/oauth2/code/{id}` | `GET /who/oidc/callback/{id}` |

`/who/me`, `/who/sessions`, `/who/email/addresses` and `/who/oidc/...` keep their paths under the
default root, and move with it if you set another.

- **The old paths are gone.** who answers none of them, and keeps no redirects.
- **A registration's `redirect-uri`.** Leave it unset and who sets its callback. An explicit one
  must end with `/who/oidc/callback/{registrationId}` (under your root); any other fails startup,
  naming `spring.security.oauth2.client.registration.<id>.redirect-uri`.
- **Code that builds who's pieces itself.** `PendingSignUpFilter`'s `PENDING_URL`, `SIGN_UP_URL`
  and `LINK_URL` are gone. `PendingSignUpFilter`, `OidcLoginMethod`, `EmailLinkLoginMethod`,
  `EmailAddressEndpoints`, `PasskeyLoginMethod` and `PasskeyRegistrationGuardFilter` take a
  `WhoPaths` first (`WhoPaths.defaults()` for `/who`).
- **Passkeys no longer go through Spring's `webAuthn()`.** who adds Spring's passkey filters to
  its chain itself, at its own paths. It no longer needs a `UserDetailsService`, and no longer
  shares its passkey repositories with the chain. An application's own `webAuthn()` chain is
  untouched and keeps Spring's paths.

### Signed-in sessions

- **A new table, `who_session`.** With `who.initialize-schema=always` who creates it. With
  Liquibase, `who-changelog-all.xml` (or `who-jdbc-changelog.xml`) has a new changeSet,
  `who-session-1`. Otherwise create it from `who-jdbc-schema.sql`.
- **Everyone signs in once more.** A browser session saved before 0.19 has no record behind it,
  so its next request is signed out. Sessions that sign in from 0.19 on keep working.
- **`WhoSession` gains a fifth component, `sessionId`**, the id of its record. The four-argument
  constructor makes a session with none, which is signed out on its next request.
- **`WhoSessionSecurityContextRepository` takes `WhoSessions`.** Its constructor is now
  `(WhoService, WhoSessions, Clock, WhoEventPublisher)`; the two- and three-argument ones are
  gone. Only an application that builds the repository itself needs to change.
- **A new sign-in must say how it signed in.** Saving a `WhoAuthenticationToken` built without a
  `SignInMethod` as a new sign-in throws `IllegalStateException`. who's own sign-ins all set it.
- **Test helpers take `WhoSessions`.** `WhoTestSessions.signedIn(identityId, credentialId)` is
  now `signedIn(whoSessions, identityId, credentialId)`, with an overload that also takes the
  sign-in time; autowire `WhoSessions` in the test. `WhoMockMvcBrowser.signedInAs` is unchanged,
  but needs a `MockMvc` built from the application context. `who-test` adds
  `InMemorySessionRecordRepository` for tests without a database.
- **New endpoints** on every browser chain: `GET /who/sessions`, `DELETE /who/sessions/{id}` and
  `DELETE /who/sessions?others=true`. They need a signed-in browser session.
- **Use `WhoSessions.endAll` to sign someone out everywhere.** The Spring Session recipe from
  earlier versions still works, but misses sessions in the container's own store.

### Recent sign-in

Nothing you have needs to change, but a few things look different:

- **Every signed-in caller has one more authority.** Beside the permissions, `getAuthorities()`
  holds a Spring Security `FactorGrantedAuthority` for how the person signed in (`FACTOR_OTT`,
  `FACTOR_WEBAUTHN`, `FACTOR_AUTHORIZATION_CODE`, or `FACTOR_BEARER` for a JWT with `auth_time`).
  Code that lists authorities as permissions should list `WhoPrincipal.permissions()` instead.
- **Sessions remember how they signed in.** `WhoSession` gains a fourth component,
  `signInMethod`. A session saved by 0.18 still loads, without it: it signs in as before, but an
  action marked `@RequiresRecentSignIn` asks it to sign in again. `WhoTestSessions.signedIn` and
  `WhoMockMvcBrowser.signedInAs` record an email-code sign-in.
- **A sign-in exactly `who.security.recent-sign-in` old is too old**, as Spring Security's factor
  check has it; it was just recent enough before.

## Upgrading to 0.18.0

0.18.0 makes who work like any Spring Boot starter: add a module's jar and it is on, and an
application with no security configuration of its own gets a working chain. It adds
[social login](social-login.md), and renames the JWT credential, and its table, so JWT and
provider sign-in can share it.

- **The starter no longer brings every module.** `who-spring-boot-starter` now brings the base
  only: identities, sessions, permissions (`who-core`, `who-spring-security`, `who-jdbc`,
  `who-rbac`) and the autoconfiguration. Add each sign-in method you use, with no version under
  the BOM:

    | You use | Add |
    |---|---|
    | Email links and codes | `who-email-link` |
    | Passkeys | `who-passkey` |
    | API keys | `who-apikey` |
    | JWTs | `who-jwt` |
    | Google, GitHub or OpenID Connect sign-in | `who-oidc` |
    | Enrollment tokens | `who-enrollment` |

- **A module on the classpath is on.** Setting `who.email-link.link-base-url` or
  `who.passkey.rp-id` no longer turns a method on; the jar does. A module whose required settings
  are missing now fails startup, and the report names the property to set and the switch that
  turns the module off:

    | Module | Fails startup without |
    |---|---|
    | `who-email-link` | `who.email-link.link-base-url`, `who.email-link.code-secret`, and an `EmailLinkSender` bean, or `spring-boot-starter-mail` with `spring.mail.host` and `who.email-link.from` |
    | `who-passkey` | `who.passkey.rp-id` |
    | `who-jwt` (when who mounts it) | `spring.security.oauth2.resourceserver.jwt.issuer-uri`, `jwk-set-uri`, `public-key-location`, or a `JwtDecoder` bean; and, unless the decoder is your own bean, `spring.security.oauth2.resourceserver.jwt.audiences` |
    | `who-oidc` | a `spring.security.oauth2.client.registration` with the `authorization_code` grant |

    To keep a module's jar but turn it off, set `who.email-link.enabled`, `who.passkey.enabled`,
    `who.api-key.enabled`, `who.jwt.enabled`, `who.oidc.enabled` or `who.enrollment.enabled` to
    `false`.

- **An application with no `SecurityFilterChain` gets who's chain instead of Spring Boot's.**
  Boot's default chains (form login and HTTP Basic, and the resource server's, Actuator's,
  and `oauth2Login`'s) are replaced by who's: every
  sign-in method on the classpath, `401` for a signed-out request, and no redirect to `/login`.
  Only `/error`, the sign-in endpoints, Actuator's health endpoint and the paths in
  `who.security.public-paths` are open to signed-out requests. List your own sign-in page and its
  assets there, including the page `link-base-url` points at:

    ```yaml
    who:
      security:
        public-paths:
          - /signin
          - /assets/**
    ```

- **An application with its own `SecurityFilterChain` is unchanged.** who's chain backs off for
  any chain of yours, and `WhoWebSecurity.passwordless()` and `WhoApiKeySecurity.apiKey()` work as
  before. To mount every method in your own chain in one call, use `WhoSecurity.all()` with
  `WhoPublicPaths`; see [Getting Started](getting-started.md#your-own-chain).
- **who no longer publishes an `AuthenticationConverter` bean.** The email-link
  `jsonOneTimeTokenAuthenticationConverter` bean is gone: Spring's resource server took it as its
  own, so an application with email links and its own `oauth2ResourceServer` refused every bearer
  token. If you replaced that bean, replace the `EmailLinkLoginMethod` bean instead.
- **`who-jwt` requires the audience.** When Spring Boot builds the decoder, startup fails until
  `spring.security.oauth2.resourceserver.jwt.audiences` is set: without it, a token the same issuer
  minted for any other application, such as a provider's ID token, would sign in. Set it to the
  audience your API's tokens carry. A `JwtDecoder` bean of your own owns that check. See
  [JWT](jwt.md#validate-the-audience).
- **`JwtCredential` is now `FederatedCredential`, in the new `who-federated` module.**
  `JwtCredential`, `JwtCredentialRepository`, `JdbcJwtCredentialRepository` and
  `JwtCredentialService` become `FederatedCredential`, `FederatedCredentialRepository`,
  `JdbcFederatedCredentialRepository` and `FederatedCredentialService`, in package
  `org.jwcarman.who.federated`. `who-jwt` brings `who-federated`, so no dependency changes. The
  methods are the same.
- **The table `who_jwt_credential` is renamed `who_federated_credential`.** Same columns, same
  rows. The bundled schema script and the Liquibase changelog rename it for you when the old table
  exists. With Flyway, add a migration:

    ```sql
    ALTER TABLE who_jwt_credential RENAME TO who_federated_credential;
    ```

    The rename is not compatible with a rolling upgrade: stop every instance on 0.17 before the
    first 0.18 instance starts.

- **`CredentialKind.JWT` is now `FEDERATED`.** `CredentialAdded` and `CredentialRevoked` for an
  `(issuer, subject)` credential carry `FEDERATED`, and the `kind` tag on `who.credentials.added`
  and `who.credentials.revoked` is `federated`, not `jwt`. Update listeners and dashboards.
  `SignInMethod.JWT` is unchanged.
- **New enum values.** `SignInMethod.OIDC`, and `SignInFailureReason.SIGN_UP_REFUSED`,
  `PROVIDER_ERROR` and `LINK_REFUSED`. An exhaustive `switch` over either needs the new cases.
- **who sets the `who_last_sign_in` cookie** after every browser sign-in, naming the method used.
  Turn it off with `who.security.last-sign-in-cookie.enabled=false`. See
  [Sessions](sessions.md#the-last-sign-in-cookie).
- **One recent sign-in setting.** `who.passkey.registration-window` and `who.oidc.link-window`
  are gone. `who.security.recent-sign-in` (default `10m`) is how recently the browser session must
  have signed in to register a passkey, link a provider account, or add or change an email address.
  Starting a provider link (`POST /who/oidc/link/{registrationId}`) now checks it too.
- **`EmailLinkSender.send` takes a `WhoEmail`.** It is sealed: the sign-in `EmailLinkMessage`, and
  the new `EmailAddressConfirmationMessage` and `EmailAddressChangedMessage`. A custom sender
  switches over the three; see [Email Links](email-links.md#turn-it-on).
- **A new table, `who_email_address_request`,** for [adding or changing an
  address](email-links.md#adding-or-changing-an-address). The bundled schema and the Liquibase
  changelog create it; Flyway users copy it from [Database Setup](database.md#flyway).
- **API keys need a lifetime and scopes.** `ApiKeyService.create(identity, name)` is now
  `create(identity, name, lifetime, scopes)` and answers a `NewApiKey`; the raw key is its
  `key()`. The lifetime is required and at most `who.api-key.max-lifetime` (default `365d`), and
  each scope must be a permission the owner holds. A key signs in with its scopes intersected
  with its owner's permissions, so a key no longer carries everything its owner can do. See
  [API Keys](api-keys.md#making-a-key).
    - New: `findByIdentity` lists an identity's keys, `rotate` replaces one with an overlap, and
      `SignInFailureReason.EXPIRED_KEY`.
    - New keys look like `who_` plus 36 base62 characters, the last 6 a checksum. A header that is
      not shaped like a key is refused without a database lookup.
    - New settings: `who.api-key.prefix`, `max-lifetime` and `last-used-interval`.
    - `ApiKeyService` and `ApiKeyAuthenticationProvider` take new constructor arguments: a
      `PermissionsResolver`, an `ApiKeyFormat` and the maximum lifetime for the service; an
      `ApiKeyFormat` and the last-used interval for the provider. Apps that use the autoconfigured
      beans change nothing.
    - `ApiKeyCredentialRepository` has two new methods, `touch` and `expireBy`, for a custom
      implementation to add.
- **The API-key tables change, and existing keys are deleted.** `who_api_key_credential` gains
  `created_at`, `expires_at` and `last_used_at`, and a new table, `who_api_key_scope`, holds each
  key's scopes. Keys made before have no lifetime or scopes, so the Liquibase changelog deletes
  them before adding the columns; issue new ones. With the bundled schema, drop
  `who_api_key_credential` and let who create both tables again. Flyway users delete the rows and
  copy the new columns and table from [Database Setup](database.md#flyway).
- **The "passkey added" startup warning moved logger.** It is now logged by
  `org.jwcarman.who.autoconfigure.WhoPasskeyAutoConfiguration`, not `WhoAutoConfiguration`.

## Upgrading to 0.17.0

0.17.0 adds [security events](events.md). There are no schema or property changes. One behavior
change breaks clients, and a few things affect applications that build who's services themselves.

- **API keys: a bad key is a `401`, even on public endpoints.** A request whose key header is
  unknown, revoked, or belongs to an inactive identity now gets the uniform `401` at once, even on
  an endpoint that permits anonymous access. Before, it continued as anonymous. A client that
  sends a stale key to a public endpoint must drop the header or use a valid key. No header, or a
  blank header, behaves as before. See [API Keys](api-keys.md#wiring-the-filter).
    - A database fault during the key check now surfaces as the original `RuntimeException`
      rather than a `ServletException`. The answer is still a `500`.
    - When a valid key signs in, an anonymous HTTP session's id is rotated.
    - Each request with an unknown, revoked or inactive key publishes `SignInFailed`; a valid key
      publishes no who event (Spring's `AuthenticationSuccessEvent` still fires).
    - A filter you pass to `WhoApiKeySecurity.apiKey(filter)` has its authentication manager and
      failure handler set by who.
- **Link JWT credentials with `JwtCredentialService`.** `link` and `unlink` publish
  `CredentialAdded` and `CredentialRevoked`; saving through `JwtCredentialRepository` directly
  does not. `unlink` takes a JWT credential id. See [JWT](jwt.md#link-a-token-to-an-identity).
- **Application tests that mock `WhoService` must stub `resolveDetailed`.** who's sign-in and
  session code now calls `WhoService.resolveDetailed` instead of `resolve`, so a mock that stubs
  only `resolve` answers `null` from `resolveDetailed`, which who treats as "not linked". Stub
  `resolveDetailed` to return the outcome you mean. `Resolution` may gain outcomes in a minor
  release, so a `switch` over it should have a `default`.
- **Use the autoconfigured `RbacService`.** `RbacService` is a `@Service`, so a component scan
  that finds it builds a second one without a publisher, which publishes no role events. Use the
  autoconfigured bean and keep it out of your scan.
- **Apps that build who's services themselves get events by passing a publisher.** Each public
  constructor you already use still works and publishes nothing. To get events, use the
  constructor that takes a `WhoEventPublisher` (and a `Clock`). With the autoconfiguration, nothing
  to do.
    - **`JsonAuthenticationHandlers`:** an application that defines its own bean with the old
      two-argument constructor publishes no `SignInSucceeded` for email link, email code or
      passkey sign-ins. Use the constructor that takes the publisher and a clock.
- **New default methods on repository SPIs.** `CredentialRemover.find`,
  `IdentityRepository.changeStatus`, `ApiKeyCredentialRepository.findById`,
  `JwtCredentialRepository.findById`, `EnrollmentTokenRepository.revokePendingForIdentity` and
  the RBAC `...IfAbsent` and `...IfPresent` methods have defaults, so existing implementations
  still compile. A custom implementation that keeps the defaults publishes less: credential
  events carry a `null` label, and no `EnrollmentTokenRevoked` is published for a token that a
  newer one replaced. Override them to get the full detail.
- **Listeners and logging.** Spring's `AuthenticationSuccessEvent` also fires for API-key
  sign-ins now. Use who's events for audit; see [Spring's own sign-in
  events](events.md#springs-own-sign-in-events).

## Upgrading to 0.16.0

0.16.0 has no schema or property changes. One small break:

- **`WhoAuthenticationToken` and `SignInFailedException` are `final`.** Both called an inherited
  method from their constructor, which a subclass could observe half-built. If you extended
  either one, wrap it or keep the extra data beside it instead.

Optional: to keep sign-in emails in the request's trace, register a `TaskDecorator` such as
Spring's `ContextPropagatingTaskDecorator`; see
[Tracing the send](email-links.md#tracing-the-send).

## Upgrading to 0.15.0

0.15.0 adds a typed sign-in code to every sign-in email. It changes the schema, a required property, and several signatures.

- **Upgrade the `who_email_link_token` table.** It gained `code_hash VARCHAR(64) NOT NULL` and `code_attempts INTEGER NOT NULL`. Sign-in links that are outstanding during the upgrade are dropped; people request a new one.
    - **Liquibase:** nothing to do. The new changeSet `who-email-link-token-2` runs at startup.
    - **Flyway, or the bundled scripts (`who.initialize-schema`):** run this once, for example as a Flyway migration. The bundled script does not change a table that already exists.

        ```sql
        DELETE FROM who_email_link_token;
        ALTER TABLE who_email_link_token
            ADD COLUMN code_hash     VARCHAR(64) NOT NULL,
            ADD COLUMN code_attempts INTEGER     NOT NULL;
        ```
    - **This is not a rolling upgrade.** Stop the 0.14.0 instances first, and apply the schema change (Liquibase or the SQL) before or as 0.15.0 starts. If the columns are missing, sign-in emails silently stop: `/ott/generate` still answers `202`, and only a WARN is logged.
- **Set `who.email-link.code-secret`.** It is required when email-link sign-in is on, at least 32 characters, and the same on every instance. Startup fails without it. See [Code secret](email-links.md#code-secret).
- **`EmailLinkSender` takes an `EmailLinkMessage`.** The method is now `send(EmailLinkMessage message)` instead of `send(String address, String link)`. The message carries `address`, `link`, `code` and `expiresAt`, and `formattedCode()` gives the grouped code. Update custom senders to include the code. See [Turn it on](email-links.md#turn-it-on).
- **`POST /login/ott` takes two body shapes.** `{"token": "..."}` as before, or `{"email": "...", "code": "..."}`.
- **New properties.** `who.email-link.code-length` (default 6), `code-max-attempts` (default 5) and `code-secret`. The default subject is now `Your sign-in link and code`.
- **Changed signatures.** Code that implements or calls these must change:
    - `EmailLinkTokenRepository`: `issue` takes the code hash, and `attemptCode` and `consumeCode` are new;
    - `EmailLinkAuthenticationProvider` is built with `(repository, settings, whoService, clock)`;
    - `WhoOneTimeTokenService` is built with `(clock, tokenTtl)`;
    - `WhoOneTimeTokenService.consume` now throws `UnsupportedOperationException`. who's `EmailLinkAuthenticationProvider` redeems sign-ins, so an application that reuses that bean with Spring's default OTT provider must switch to who's provider.

## Upgrading to 0.14.0

0.14.0 stops who from writing error responses. who now throws exceptions and your application formats them (see [Errors](errors.md)). There are no schema changes.

- **No more `{"error": …}` bodies.** Sign-in failures, passkey registration errors, signed-out requests and denied requests no longer answer with `{"error":"invalid_token"}`, `{"error":"invalid_credential"}`, `{"error":"reauthentication_required"}` or `{"error":"invalid_registration"}`. Each becomes an exception that reaches your `@RestControllerAdvice`. Without an advice, the status stays the same (401, 400 or 403) and the body is your application's default error body. A client that read `error` should switch to your application's format.
- **Your advice now sees who's errors.** Errors raised inside Spring Security's filters used to bypass your `@RestControllerAdvice`; now they reach it. An advice with a catch-all `@ExceptionHandler(Exception.class)` would answer every signed-out request and failed sign-in with its own status, often a 500. Map who's exceptions (see [Errors](errors.md)) before upgrading, or keep the catch-all from matching them. An advice limited by `basePackages`, `assignableTypes` or `annotations` never sees them; who's errors need a global advice.
- **New entry point and access-denied handler.** `WhoWebSecurity.passwordless()` now installs an entry point and an access-denied handler that hand off `NotSignedInException` and `AccessDeniedException`. They replace the bare 401 and Spring's empty 403. A chain that set its own `exceptionHandling` after applying `passwordless()` keeps its own.
- **`JsonAuthenticationHandlers`.** The constructor is now `(JsonMapper, WhoExceptionHandoff)`, and `failure(String)` is gone; `failure()` hands off a `SignInFailedException`.
- **`PasskeyRegistrationGuardFilter`.** Its constructor takes a `WhoExceptionHandoff` instead of a `JsonMapper`.
- **`WhoMeController`.** `GET /who/me` throws `NotSignedInException` when no one is signed in, instead of answering a bare `401`.

## Upgrading to 0.13.0

0.13.0 is additive: `WhoService.deactivate(identityId)` and `reactivate(identityId)` take an identity
out of service and put it back, and `who-test` gains `WhoMockMvcBrowser.performWithoutCsrf`. Nothing
needs to change, and there are no schema changes.

## Upgrading to 0.12.0

0.12.0 makes sessions ready for Spring Session, adds RBAC lookups and a test module, and has five changes that can affect existing code. There are no schema changes.

- **No saved requests.** `WhoWebSecurity.passwordless()` now uses a `NullRequestCache`. A request denied with `401` is no longer saved in the session, so sign-in no longer leads to a redirect to a saved request. An SPA never relied on it, because it receives a plain `401`. If your chain redirected to the saved request, keep the target in your app instead. See [Sessions](sessions.md#no-saved-requests).
- **`EmailLinkIssuer.issue` returns a value.** It returns an `EmailLinkOutcome` instead of `void`. Source that calls it still compiles, but code built against 0.11 must be recompiled, because the method's signature in the class file changed. A subclass or a mock that overrides `issue` must change its return type. See [Sending a link from your own code](email-links.md#sending-a-link-from-your-own-code).
- **`RoleAlreadyExistsException`.** Creating a role whose name is taken now throws `RoleAlreadyExistsException` instead of a plain `IllegalArgumentException`, with a message that does not name the role. It extends `IllegalArgumentException`, so existing `catch` blocks keep working.
- **New RBAC repository methods.** `RoleRepository` gains `findAll()` and `IdentityRoleRepository` gains `findIdentityIdsByRoleId(UUID)`. An app with its own implementation of either interface must implement them, or it will not compile. The JDBC repositories who ships already do.
- **A new session attribute.** The session now also holds the identity id (as a string) under Spring Session's principal-name index attribute. With Spring Session, `FindByIndexNameSessionRepository.findByPrincipalName(identityId.toString())` finds the sessions of an identity. A test that asserts the exact attributes of a session must expect it. Sessions created before the upgrade have no such attribute until the user signs in again. See [Sessions](sessions.md).
- **New.** `RbacService.findRole`, `listRoles` and `findIdentityIdsWithRole`; the `who-test` module ([Testing](testing.md)); native serialization hints for sessions, so apps that added their own can drop them ([Native Images](native.md)).

## Upgrading to 0.11.0

0.11.0 adds email-link sign-in, passkeys and sessions. It also changes every timestamp column to `TIMESTAMP WITH TIME ZONE`.

### Convert the timestamp columns first

Existing databases created by 0.10.0 or earlier must run these statements before the upgrade. Who 0.11.0 reads and writes timestamps as UTC values, and reading the old `TIMESTAMP` columns may fail. The `CREATE TABLE IF NOT EXISTS` schema does not change existing tables.

The old columns hold local time without a zone. Use the time zone the JVM ran in, here `UTC`:

```sql
ALTER TABLE who_identity
    ALTER COLUMN created_at TYPE timestamptz USING created_at AT TIME ZONE 'UTC',
    ALTER COLUMN updated_at TYPE timestamptz USING updated_at AT TIME ZONE 'UTC';

ALTER TABLE who_enrollment_token
    ALTER COLUMN created_at  TYPE timestamptz USING created_at  AT TIME ZONE 'UTC',
    ALTER COLUMN expires_at  TYPE timestamptz USING expires_at  AT TIME ZONE 'UTC',
    ALTER COLUMN redeemed_at TYPE timestamptz USING redeemed_at AT TIME ZONE 'UTC';
```

Replace `UTC` with the zone of the JVM (for example `Europe/Berlin`) if it was not UTC. These statements are for PostgreSQL. New schemas spell the type `TIMESTAMP WITH TIME ZONE`, which H2 and PostgreSQL both accept.

### Other changes

- **New tables.** Email-link sign-in adds `who_email_credential` and `who_email_link_token`. The bundled schema creates them. If you manage the schema with Flyway, copy them from [Database Setup](database.md#flyway).
- **Passkeys are opt-in.** The new `who-passkey` module adds the tables `who_passkey_user` and `who_passkey_credential`. The bundled schema creates them. If you manage the schema with Flyway, copy them from [Database Setup](database.md#flyway). Passkey storage is PostgreSQL only. Nothing changes until you set `who.passkey.rp-id`. See [Passkeys](passkeys.md).
- **Schema files renamed.** Each bundled schema file now starts with its module name. If you point `spring.sql.init.schema-locations` or your own tooling at them, update the paths:
    - `org/jwcarman/who/jdbc/who-jdbc-schema.sql`
    - `org/jwcarman/who/rbac/who-rbac-schema.sql`
    - `org/jwcarman/who/jwt/who-jwt-schema.sql`
    - `org/jwcarman/who/enrollment/who-enrollment-schema.sql`
    - `org/jwcarman/who/apikey/who-apikey-schema.sql`
    - `org/jwcarman/who/emaillink/who-email-link-schema.sql`
    - `org/jwcarman/who/passkey/who-passkey-schema.sql` (new)
- **Liquibase.** The long YAML changelog in the old guide is gone. Include `classpath:org/jwcarman/who/db/who-changelog.xml` instead. See [Liquibase](database.md#liquibase). Do not run it over tables that were created by hand without `changelog-sync` first.
- **Email links are opt-in.** Nothing changes for an application that uses only JWT or API keys. To turn them on, see [Email Links](email-links.md).
- **Sessions.** `WhoWebSecurity.passwordless()` is new and applies only to chains that call it. Other chains are unchanged.

## Upgrading to 0.10.0

0.10.0 is a security release. Some changes break existing code or data.

- **API key filter wiring.** `ApiKeyAuthenticationFilter` is no longer registered globally with the servlet container. Add it to each chain that should accept API keys: `http.with(WhoApiKeySecurity.apiKey(), Customizer.withDefaults())`. The filter skips requests that are already authenticated. See [Wiring the filter](api-keys.md#wiring-the-filter).
- **Method security is on by default.** `@RequiresPermission` is now enforced without any setup. Set `who.method-security.enabled=false` to turn it off. An `@EnableMethodSecurity` in your own configuration is respected.
- **`ApiKeyCredentialRepository.deleteById` returns `boolean`.** It returns `true` if a key was deleted. Update custom implementations. To revoke a key, call `ApiKeyService.revoke(UUID)`.
- **New key and token format.** New API keys are `who_` plus 43 URL-safe characters (256 random bits). Enrollment tokens use the same random source. Existing API keys keep working. Pending enrollment tokens issued before 0.10.0 are predictable (the token value followed from its id), so revoke them and issue new ones:

  ```sql
  UPDATE who_enrollment_token SET status = 'REVOKED' WHERE status = 'PENDING';
  ```
- **Enrollment exceptions.** `enroll` throws `EnrollmentIdentityInactiveException` for an identity that is not active, and `CredentialAlreadyLinkedException` for a credential that is already linked. A token is single use, even under concurrent requests. `EnrollmentTokenNotFoundException` no longer contains the token value, and `EnrollmentToken.toString()` masks the value.
- **JWT claims.** A token with no `iss` or no `sub` claim now gets 401 instead of 500. Validate the issuer too. See [Configure JWT](jwt.md).
- **Foreign key on `who_identity_role`.** `who_identity_role.identity_id` now references `who_identity(id)` with `ON DELETE CASCADE`. Deleting an identity removes its role assignments, and assigning a role to an identity that is not in `who_identity` now fails. The `CREATE TABLE IF NOT EXISTS` schema does not change existing tables, so on an existing database run this yourself. Delete orphan rows first, or the constraint fails:

```sql
DELETE FROM who_identity_role
WHERE identity_id NOT IN (SELECT id FROM who_identity);

ALTER TABLE who_identity_role
    ADD CONSTRAINT fk_identity_role_identity
    FOREIGN KEY (identity_id) REFERENCES who_identity(id) ON DELETE CASCADE;
```
