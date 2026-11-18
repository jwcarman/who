# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Breaking changes

- **Every who email goes through `WhoEmails`.** It refuses inside a transaction, renders the email,
  and hands the result to a `WhoEmailSender`.
- **`WhoEmailSender` moved to who-core and delivers only.** It takes a rendered `OutgoingEmail`
  instead of a `WhoEmail`. `WhoEmail` moved to who-core too, is no longer sealed, and has
  `template()`.
- **Sending moved out of `who-email-link`.** Add `who-email` (rendering) and `who-email-smtp` (SMTP
  delivery). `MailWhoEmailSender` is replaced by `SmtpWhoEmailSender`.
- **`who.email.from` replaces `who.email-link.from` and `who.passkey.from`.**
- **Subjects are message keys.** `who.email.<template>.subject` replaces
  `who.email-link.subject`, `invitation-subject`, `address-confirmation-subject` and
  `address-changed-subject`.
- **`MailPasskeyAddedNotifier` is removed.** The passkey-added email goes through `WhoEmails` and is
  sent whenever email-link sign-in is on.
- **`EmailLinkMessage`, `InvitationMessage` and `EmailAddressConfirmationMessage` gain `validFor`.**
- **who answers every error under its base path itself**, as Problem Details
  (`application/problem+json`: `type` `urn:who:error:<code>`, `title`, `status`, `detail`, `code`),
  whatever the application's advice does. Every error there has a code. Outside the base path the
  application's advice still answers who's exceptions, with the `Who-Error` header.
- **`invalid-request` replaces `invalid-admin-request`, `invalid-api-key-request` and
  `invalid-invitation`**, in the `Who-Error` header and in the TypeScript client, whose
  `InvalidAdminRequest` and `InvalidApiKeyRequest` types become `InvalidRequest`.
- **`WhoExceptionHandoff` takes `WhoPaths`.** `WhoExceptionHandoff.from(BeanFactory)` gives an
  application context's handoff.
- **`PasskeyLoginMethod` no longer takes a `PasskeyService` or a `LastCredentialGuard`.**
  Listing and removing passkeys is the `PasskeysController` bean's, so a `PasskeyLoginMethod`
  built by hand drops its last two arguments.
- **`OidcLoginMethod` no longer takes a `FederatedCredentialService` or a `LastCredentialGuard`.**
  Listing and unlinking accounts is the `LinkedAccountsController` bean's.
- **who's resource endpoints are controllers.** `SessionsFilter`, `EmailAddressEndpoints`,
  `LinkedAccountsEndpoints`, `ApiKeyEndpoints`, `WhoAdminEndpoints` and `WhoInvitationEndpoints`
  are removed; `SessionsController`, `EmailAddressesController`, `PasskeysController`,
  `LinkedAccountsController`,
  `ApiKeysController`, `IdentitiesController`, `RolesController` and `InvitationsController` are
  beans of their modules' auto-configuration, on with the module. The admin controllers share an
  `AdminGate` bean.
  The application's `authorizeHttpRequests` rules now apply to these endpoints, as to any
  controller. An application controller mapped to one of their paths now fails startup as an
  ambiguous mapping instead of being shadowed, and who's resource endpoints need Spring MVC.
  A controller path prefix or `spring.mvc.servlet.path` that would move who's controllers away
  from `who.base-path` fails startup.
- **`WhoJson` is removed.** who's answer types pin their JSON shape with Jackson annotations
  instead. `WhoMeController.me` returns its `WhoMeResponse` and no longer takes the response.

### Added

- `who-email`: renders who's emails from Mustache templates. An application replaces any email's
  text, or adds HTML, by dropping a file in `classpath:/templates/who/email/`; values come from the
  email, `who.email.model.*` and `WhoEmailModelContributor` beans. Templates compile at startup.
- `who-email-smtp`: SMTP delivery through Spring's `JavaMailSender`, multipart when there is HTML.
- Emails are rendered in the request's locale, or `who.email.locale`.

### Changed

- The TypeScript client reads a failure's code from the `Who-Error` header, or, where CORS hides
  it, from the body's `code`.

### Fixed

- Native images answer `GET /who/me` and every resource endpoint. They answered `500` ("Record
  components not available") since 0.21.0, because no answer type was registered for reflection.
- A passkey's label must be 1 to 64 characters, not blank, with no control characters; anything else answers
  `400` `invalid-passkey-registration`. The label appears in the owner's "passkey added" email, so
  a label with line breaks could have passed off text of its own there.
- who's 5xx log lines escape control characters in the request's method and URI, so a request
  cannot forge a log line.

## [0.22.0] - 2026-11-17

### Added

- `InvitationService.invite(email, roleNames, callback)`: the callback receives the new identity and runs inside the invitation's transaction, on the caller's thread, after the roles are assigned and before `IdentityInvited` is published. It is the way to write the application's own data (a profile row, an activity-log entry) so it commits or rolls back with the invitation. If it throws, the whole invitation rolls back, nothing is sent, and a `RuntimeException` or `Error` reaches the caller unchanged (a sneaked checked exception arrives wrapped in `UndeclaredThrowableException`).

## [0.21.0] - 2026-11-17

### Breaking changes

- `EmailLinkSender` is now `WhoEmailSender`, and the default `MailEmailLinkSender` is `MailWhoEmailSender`: it sends every who email, not only sign-in links. There is no alias; a custom sender implements `WhoEmailSender`, and an application that names the auto-configured bean (`mailEmailLinkSender`) calls it `mailWhoEmailSender`.
- who's HTTP surface is REST-shaped: a person's own things live under `/who/me`, and the pending provider sign-up under `/who/oidc/pending`. The old paths reach no who endpoint, and there are no redirects:

  | Before | Now |
  |---|---|
  | `GET /who/sessions` | `GET /who/me/sessions` |
  | `DELETE /who/sessions/{id}` | `DELETE /who/me/sessions/{id}` |
  | `DELETE /who/sessions?others=true` | `DELETE /who/me/sessions?others=true` |
  | `POST /who/passkey/register/options` | `POST /who/me/passkeys/options` |
  | `POST /who/passkey/register` | `POST /who/me/passkeys` |
  | `DELETE /who/passkey/register/{id}` | `DELETE /who/me/passkeys/{credentialId}` |
  | `POST /who/email/addresses` | `POST /who/me/email-addresses` |
  | `POST /who/email/addresses/confirm` | `POST /who/me/email-addresses/confirmations` |
  | `POST /who/oidc/link/{registrationId}` | `POST /who/me/accounts/{registrationId}` |
  | `POST /who/oidc/sign-up` | `POST /who/oidc/pending/sign-up` |
  | `POST /who/oidc/link` | `POST /who/oidc/pending/link` |

  See [Upgrading](docs/guides/upgrading.md#upgrading-to-0210).
- Removing a passkey is who's own endpoint, no longer Spring's: it needs a browser session signed in within `who.security.recent-sign-in`, answers `404` (not `403`) for a passkey that is someone else's or unknown, and `409` for the identity's last credential. Spring's delete endpoint is turned off.
- `who-passkey`, `who-email-link` and `who-oidc` need the new `LastCredentialGuard` bean, which who builds when there is `who-jdbc` and exactly one `PlatformTransactionManager`. Without it they fail startup (`NoSuchBeanDefinitionException`). Mark one transaction manager `@Primary`, or define the guard yourself.
- Custom repositories must implement new methods: `IdentityRepository.lockById`, `findPage(status, roleId, ids, page, size)` and `count(status, roleId, ids)` (a role filter is the who-rbac role's id, matched in the query, so its holders are never listed first); `EmailCredentialRepository.findAllByIdentityId`; `FederatedCredentialRepository.findAllByIdentityId`.
- Constructors that take new arguments: `PasskeyLoginMethod` (`PasskeyService`, `LastCredentialGuard`), `OidcLoginMethod` (`FederatedCredentialService`, `LastCredentialGuard`), `EmailAddressEndpoints` (`EmailCredentialService`, `LastCredentialGuard`), `WhoMeController` (`WhoSessionSecurityContextRepository`, `RecentSignInAuthorizationManager`, `ObjectProvider<LastCredentialGuard>`; an application that imports it by hand needs a `RecentSignInAuthorizationManager` bean), and `WhoPublicPaths`, now `(WhoPaths, methods, configured, contributed)`, with no other constructor. `WhoMeController.me` takes `(HttpServletRequest, HttpServletResponse, Authentication)` and writes its answer itself. `SessionEndpoints` no longer takes a `JsonMapper`. `WhoSessionSecurityContextRepository`'s four-argument constructor is gone: its one constructor also takes the `RequestMatcher` for requests that present a stateless credential (`_ -> false` when there is none).
- who writes the JSON of `GET /who/me`, `GET /who/sign-in`, everything under `/who/me/` and the admin and invitation endpoints with its own Jackson mapper, not the application's, so `spring.jackson.*` settings no longer change them: names stay camelCase, instants stay ISO-8601 strings, and a `null` stays `null`. An application that set, say, `spring.jackson.property-naming-strategy=SNAKE_CASE` and read `identity_id` from `/who/me` reads `identityId` now.
- `WhoMeController.WhoMeResponse` gains `signInMethod`, `recentSignInUntil` and `credentials`, so its constructor changes; `GET /who/me`'s JSON gains the three fields.
- A session cookie with an API key or a bearer token beside it is no longer a browser session anywhere who asks for one: the session, passkey, email-address, linked-account and API-key endpoints answer it `403` `browser-session-required`, and so do the admin endpoints when it changes something. Those `403`s were Spring's `AccessDeniedException`; they are now `BrowserSessionRequiredException`, or `ApiKeyCannotManageKeysException` for an API key managing keys, both subclasses of it. Every such check now classifies the caller by what signed the request in, with `WhoSessionSecurityContextRepository.caller(...)` and its `WhoCaller`; a signed-in caller that is not a browser session gets `403` at starting a link and at passkey registration, where it got `401` before. `POST /who/me/email-addresses` and `/confirmations` answer a signed-in caller that is not a browser session `403` instead of `401`, and so does starting a link.
- On a chain with sessions, a bearer token beside a live session cookie is ignored: the session signs the request in, and the request still needs its CSRF token. Before, the token signed it in and skipped CSRF. `JwtLoginMethod` wraps Spring's bearer-token converter so the resource server ignores the token too; a custom `StatelessLoginMethod` that configures Spring's resource server must do the same, and its `carriesCredential()` should not match when `StatelessLoginMethod.alreadySignedIn()`.
- `ApiKeyAuthenticationFilter` sits before `ExceptionTranslationFilter` (it was before `AuthorizationFilter`), so who's endpoints, which sit right after that filter, see a key's sign-in. A chain that orders its own filters against the old position may need to move them.
- `GET /who/sign-in` is who's, and public: an application endpoint at that path is shadowed.
- The TypeScript client follows the new layout. Calls that moved, with no aliases:

  | Before | Now |
  |---|---|
  | `who.me()` | `who.me.get()` |
  | `who.sessions.list()`, `end(id)`, `endOthers()` | `who.me.sessions.list()`, `end(id)`, `endOthers()` |
  | `who.passkeys.register(label)` | `who.me.passkeys.register(label)` |
  | `who.passkeys.remove(credentialId)` | `who.me.passkeys.remove(credentialId)` |
  | `who.email.addAddress(address, replaces?)` | `who.me.emailAddresses.add(address, replaces?)` |
  | `who.email.confirmAddress(proof)` | `who.me.emailAddresses.confirm(proof)` |
  | `who.email.confirmAddressFromLocation()` | `who.me.emailAddresses.confirmFromLocation()` |
  | `who.oidc.startLink(registrationId)` | `who.me.accounts.link(registrationId)` |

  `Me` gains `signInMethod`, `recentSignInUntil` and `credentials`. The account calls and the admin changes gain `'browser-session-required'`, and the API-key calls `'api-key-cannot-manage-keys'`, where a `403` with that `Who-Error` was thrown as `'unexpected'` (or, for an admin change, reported as `'permission-required'`). `confirm` answers `{ credentialId }` instead of nothing. `me.passkeys.remove` fails with `'signed-out'`, `'reauthentication-required'`, `'sign-in-required'`, `'not-found'` or `'last-credential'` instead of `'signed-out'` or `'refused'`. The sign-in calls (`email.send`, `email.signIn`, `passkeys.signIn`, `oidc.*`) keep their names.

### Added

- `InvitationMessage(address, link, code, expiresAt, roles)`, the email of an invitation. `InvitationService` now has its link sent as one instead of an `EmailLinkMessage`, so a `WhoEmailSender` can word an invitation differently from a sign-in; `WhoEmail` permits it, so a custom sender must handle it: one written with `instanceof EmailLinkMessage` checks, or with a `switch` that has a `default ->` branch, still compiles but silently drops every invitation (who still reports the link as sent), so use an exhaustive `switch` without `default`. The default sender says it is an invitation, with the subject `who.email-link.invitation-subject` (`You have been invited`). `MailWhoEmailSender`'s six-argument constructor gains the invitation subject before the clock.
- The caller's own account under `/who/me`, each endpoint in the module that owns its data, checking ownership itself (another identity's id is `404`, as an unknown one is). See [Your Account](docs/guides/account.md).
  - `GET /who/me/passkeys`, `GET /who/me/email-addresses`, `DELETE /who/me/email-addresses/{id}`, `GET /who/me/accounts` and `DELETE /who/me/accounts/{id}`.
  - `GET` and `POST /who/me/api-keys`, `POST /who/me/api-keys/{id}/rotations`, `DELETE /who/me/api-keys/{id}` for a browser session or a bearer token, and `GET /who/me/api-keys/current` for the key a request signed in with. A key never manages keys, and the raw key appears only in the two `201` answers. A browser needs a recent sign-in to make or rotate one.
  - Every timestamp in these answers ends in `At`, as the sessions list's `signedInAt` and `lastSeenAt` do: `createdAt` and `lastUsedAt` on passkeys and API keys, `createdAt` on email addresses and admin identities, `linkedAt` on accounts, `expiresAt` on API keys.
  - `GET /who/me` reports how the request signed in (`signInMethod`, in the sessions list's spelling), until when a browser's sign-in is recent (`recentSignInUntil`), and the identity's sign-in credentials by kind (`credentials`).
- The last-credential rule: removing a passkey, an email address or a linked account through `/who/me` answers `409` (`LastCredentialException`, `Who-Error: last-credential`) when it would leave the identity no way to sign in. The count and the removal hold the identity's row lock. `LastCredentialGuard` (constructed with the `PlatformTransactionManager`, whose transaction it runs at `READ COMMITTED` whatever the database's default) and `SignInCredentialCounter` apply it in your own code; call it outside a transaction of yours, which it would join at your isolation. A linked account counts only when a configured provider registration signs it in (`OidcSignInCredentialCounter`, from `who-oidc`): one whose registration was removed, and a JWT's issuer and subject, do not count, and without `who-oidc` no linked account does.
- `GET /who/sign-in`, public: which sign-in methods are on and the providers to draw buttons for. `PasswordlessLoginMethod.signInOptions()` is what each method contributes.
- `who-admin`, endpoints for an admin screen, guarded by two permissions who defines and grants to no one, `who.identities.read` and `who.identities.manage` (`WhoAdminPermissions`): `GET /who/identities` (paged, filtered by `status`, `role` and `email`), `GET` and `PATCH /who/identities/{id}` (`{"status": "ACTIVE" | "DISABLED"}`), `PUT` and `DELETE /who/identities/{id}/roles/{role}`, and `GET /who/roles`. A browser session that changes something needs a recent sign-in; a caller may grant only roles within its own permissions; an admin cannot deactivate themselves. `who.admin.enabled=false` turns them off. See [Admin](docs/guides/admin.md).
- Admins cannot act above their rank: changing the status or the roles of an identity that holds a permission the caller does not, as who resolves it for that identity whatever its status, is `403` `IdentityOutranksCallerException` (`identity-outranks-caller`). Acting on yourself always passes. `WhoService.permissionsOf(identity)` resolves an identity's permissions whatever its status.
- The last-admin rule: an admin endpoint's deactivation or role removal that would leave no active identity holding `who.identities.manage` is `409` `LastAdminException` (`last-admin`). `LastAdminGuard` counts and changes in one `READ COMMITTED` transaction holding the row locks of the roles that grant the permission, so two concurrent demotions cannot both pass. It counts who-rbac's holders: with a `PermissionsResolver` of your own it refuses nothing and logs a warning at startup. The admin endpoints now need exactly one `PlatformTransactionManager`, as the credential endpoints do.
- Invitations: `InvitationService.invite(email, roles)` in `who-email-link` creates the identity, registers the address and assigns the roles in one transaction, then sends the sign-in link after it commits; `POST /who/invitations` (who-admin) is the endpoint over it. Call `invite` outside any transaction. The new event `IdentityInvited` is published inside the invitation's transaction, so a listener can store the application's own data with it, and a listener that throws rolls the invitation back; at `POST /who/invitations` its exception goes through the application's exception handling, so its `@ResponseStatus` or a `@RestControllerAdvice` answers it (`500` otherwise), a `DuplicateKeyException` of its own included. `invite` throws `EmailAddressTakenException` for an address a concurrent invitation or registration took first. `who-rbac` is now an optional dependency of `who-email-link`, for invitation roles.
- `WhoPublicPathContributor`: a bean that adds paths a signed-out request may reach, beside `who.security.public-paths`, for libraries and apps alike. Contributions only add, are logged at startup with their bean, and are in `WhoPublicPaths`. A null or blank path fails startup. An application that replaces the `WhoPublicPaths` bean drops every contribution.
- `StatelessEndpoints`, for endpoints that go on every stateless chain, as `BrowserEndpoints` do on browser chains. `StatelessLoginMethod.presentsCredential()` and `alreadySignedIn()`, and `WhoSessionSecurityContextRepository.caller(...)` (a `WhoCaller`: signed out, a browser session, a session with a key or token beside it, a bearer token or API key, or another sign-in), the one way to tell a browser session from a request carrying a key or a token.
- New `Who-Error` codes: `browser-session-required` (`BrowserSessionRequiredException`, an `AccessDeniedException`: a bearer token or an API key where only a browser session may act, a session cookie with one beside it, or a sign-in no session loaded), `api-key-cannot-manage-keys` (`ApiKeyCannotManageKeysException`: an API key at the API-key endpoints, apart from `/current`), `session-not-found`, `passkey-not-found`, `last-credential`, `linked-account-not-found`, `api-key-not-found`, `invalid-api-key-request`, `permission-required`, `role-grant-forbidden`, `identity-not-found`, `unknown-role`, `invalid-admin-request`, `self-deactivation`, `invalid-invitation`, `identity-outranks-caller` and `last-admin`. See [Errors](docs/guides/errors.md).
- `PasskeyService.ownerOf`, `EmailCredentialService.findAllFor`, `EmailCredential.created()`, `FederatedCredentialService.findAllFor` and `isOwnedBy`, `FederatedCredential.linkedAt()`, `ApiKeyService.findOwnedBy`, `RbacService.findRolesForIdentity` and `findPermissions`, and `Identifiers.createdAt`.
- The TypeScript client covers the new endpoints: `who.signInOptions()`, `who.me.*` (`passkeys.list`, `emailAddresses.list` and `remove`, `accounts.list` and `unlink`, `apiKeys.*`) and `who.admin.*`.

### Fixed

- `InvitationService` issues the sign-in link with no transaction open. It issued it inside a `REQUIRES_NEW` transaction, so the email went out before that transaction committed, and a failed commit left the person a dead link, while a database connection was held for the mail round trip. `POST /who/email/send` never did either.
- An exception that nothing in the application answered and that `WhoExceptionHandoff` sent as a `5xx` (such as an `IdentityInvited` listener's at `POST /who/invitations`) was logged nowhere. who now logs it at `ERROR` with its stack trace.
- Ids that who made in the same millisecond had no order, because each call built a new UUIDv7 generator, so lists that are oldest first could show two credentials made together in either order. who now keeps one generator, whose ids always increase.

## [0.20.0] - 2026-11-17

### Breaking changes

- `EmailAddressEndpoints` implements the new `BrowserEndpoints` instead of `PasswordlessLoginMethod`: it is not a way to sign in. A browser chain applies every `BrowserEndpoints` bean after its sign-in methods, so the email-address endpoints work as before; code that looked them up as a `PasswordlessLoginMethod` no longer finds them.

- `email.send` in the TypeScript client returns a `WhoResult` instead of `void`, so a page can see `'human-check-failed'`.

### Added

- A human check on `POST /who/email/send` and `POST /who/email/sign-in`: with a `WhoHumanCheck` bean, who refuses a request the check does not pass before the endpoint runs, answering `403` with `Who-Error: human-check-failed` (`HumanCheckFailedException`). `who-turnstile` provides Cloudflare Turnstile (`who.turnstile.secret-key`, `who.turnstile.verify-url`); a `TurnstileVerifier` bean decides what Cloudflare's answers pass, and Cloudflare's test keys pass on success alone, with a startup warning. Each check publishes `HumanCheckPerformed` and counts in `who.human-checks`. Turnstile calls Cloudflare with a client of its own, built from the application's HTTP client settings (`who-turnstile` brings `spring-boot-http-client`) with `who.turnstile.timeout` as its timeouts; the application's `RestClient.Builder`, headers and interceptors never reach Cloudflare. The TypeScript client's email calls take `{ humanCheck }`. See [Email Links](docs/guides/email-links.md#human-check).
- An Agent Skill for Claude Code and other coding agents: `/plugin marketplace add jwcarman/who`, then `/plugin install who@who`. It carries who's guides and this changelog, which CI keeps in step with the repository, and the rules for adding who to an app. See [Agent Skill](docs/agent-skill.md).
- The docs site publishes [`llms.txt`](https://jwcarman.github.io/who/llms.txt), `llms-full.txt` and a Markdown copy of each page, so coding agents can read the guides without a browser.
- At startup, each who security chain logs at INFO the sign-in methods it accepts, such as `who: this chain signs browsers in with email-link, passkey, oidc (github, google) under /who` and `who: this chain accepts api-key, jwt`. `PasswordlessLoginMethod` and `StatelessLoginMethod` gain `name()`, which defaults to the class's simple name. See [Observability](docs/guides/observability.md#startup-log).

## [0.19.0] - 2026-11-16

### Breaking changes

- Every browser session saved before 0.19 is signed out on its next request: each session now has a record in the new `who_session` table, checked on every request. Create the table (`who.initialize-schema`, the `who-session-1` changeSet, or `who-jdbc-schema.sql`).
- `WhoSessionSecurityContextRepository`'s constructor is now `(WhoService, WhoSessions, Clock, WhoEventPublisher)`. Saving a new sign-in whose token has no `SignInMethod` throws `IllegalStateException`.
- A session timeout of `0` or less (`server.servlet.session.timeout` or `spring.session.timeout`), meaning sessions never expire, now fails startup: who needs sessions to expire to know which records are abandoned.
- `WhoTestSessions.signedIn` takes the application's `WhoSessions` first. `WhoMockMvcBrowser.signedInAs` needs a `MockMvc` built from the application context.
- Every who endpoint moves under one root, `who.base-path` (default `/who`). `POST /ott/generate` is now `POST /who/email/send`; `POST /login/ott`, `POST /who/email/sign-in`; `POST /logout`, `POST /who/logout`; `POST /webauthn/register/options` and `POST /webauthn/register`, `POST /who/passkey/register/options` and `POST /who/passkey/register`; `DELETE /webauthn/register/{id}`, `DELETE /who/passkey/register/{id}`; `POST /webauthn/authenticate/options`, `POST /who/passkey/sign-in/options`; `POST /login/webauthn`, `POST /who/passkey/sign-in`; `GET /oauth2/authorization/{id}`, `GET /who/oidc/authorize/{id}`. The old paths reach no who endpoint.
- The provider callback is now `{baseUrl}/who/oidc/callback/{registrationId}`. Register the new URL with each provider. A registration with an explicit `redirect-uri` who does not serve fails startup, naming `spring.security.oauth2.client.registration.<id>.redirect-uri`.
- `PendingSignUpFilter.PENDING_URL`, `SIGN_UP_URL` and `LINK_URL` are gone. The constructors of `PendingSignUpFilter`, `OidcLoginMethod`, `EmailLinkLoginMethod`, `EmailAddressEndpoints`, `PasskeyLoginMethod` and `PasskeyRegistrationGuardFilter` take `WhoPaths` first.
- Passkeys no longer use Spring Security's `webAuthn()`: who adds Spring's passkey filters to its chain itself, at its own paths, and no longer shares a stub `UserDetailsService` or its passkey repositories with the chain.

See [Upgrading](docs/guides/upgrading.md#upgrading-to-0190).

### Added

- `@jwcarman/who-client`, a typed TypeScript client for who's browser endpoints, published to npm with each release. See [TypeScript Client](docs/guides/client.md).
- Every failure who hands to your application carries a `Who-Error` response header naming it, such as `reauthentication-required`, beside whatever body your application writes. See [Errors](docs/guides/errors.md#the-who-error-header).
- `who.base-path`: one configurable root for every who endpoint, including the provider callback, and the `WhoPaths` bean that resolves paths under it. See [Configuration](docs/guides/configuration.md#endpoints).
- People can see where they are signed in and sign any browser out: `GET /who/sessions`, `DELETE /who/sessions/{id}` and `DELETE /who/sessions?others=true` on every browser chain. Ending another session needs a recent sign-in; ending your own never does. The list shows a device label, the sign-in method and times, and who's own id, never the session cookie, and keeps no IP address. See [Sessions](docs/guides/sessions.md#see-and-end-sessions).
- `WhoSessions`, for your own code: `list`, `end`, `endAll`, `endAllExcept` and `purgeEndedSessions`. Ended sessions publish `SessionEnded` with the new reason `REVOKED`.
- Ended and abandoned session records are deleted 100 at a time on each sign-in (`who.sessions.auto-purge`, `who.sessions.purge-batch-size`), publishing `EndedSessionsPurged` and counted by the `who.sessions.purged` meter. `who.sessions.last-seen-interval` (default `5m`) limits how often a session's last use is written. A purge that fails is logged and never fails the sign-in.
- Signing out the current browser through `DELETE /who/sessions/{id}` runs the chain's logout handlers, as `POST /logout` does. The session endpoints answer only the authentication the browser session loaded, so an API key or bearer token sent beside a session cookie gets `403`.
- `InMemorySessionRecordRepository` in `who-test`, for tests without a database.

- `@RequiresRecentSignIn`: an action, on a method or a type, that needs a sign-in within `who.security.recent-sign-in` or its own `within`. A stale browser session gets `401` `ReauthenticationRequiredException`; a JWT whose `auth_time` is too old gets `401` `InsufficientUserAuthenticationException` with the RFC 9470 step-up challenge (`error="insufficient_user_authentication"`, `max_age`); a JWT without `auth_time`, and an API key, get `403` `RecentSignInUnavailableException`. The `RecentSignInAuthorizationManager` bean does the same for URL rules. See [Recent sign-in](docs/guides/security-model.md#recent-sign-in).
- Every who sign-in carries a Spring Security `FactorGrantedAuthority` issued when the person signed in: `FACTOR_OTT`, `FACTOR_WEBAUTHN`, `FACTOR_AUTHORIZATION_CODE`, or `FACTOR_BEARER` at a JWT's `auth_time`. API keys carry none. `WhoAuthenticationToken.signInFactor()` and `WhoAuthenticationToken.signedIn(...)` are new.

### Changed

- `WhoSession` records how the session signed in. A session saved before 0.19 still loads, but must sign in again for an action marked `@RequiresRecentSignIn`.
- who's own recent-sign-in checks (adding a passkey, a provider account or an email address) share the rule, and a sign-in exactly one window old is now too old.
- A recent-sign-in refusal's step-up challenge (`WWW-Authenticate: Bearer error="insufficient_user_authentication"`) is sent only with a `401`, on who's chains. An application that answers the refusal with another status, or catches it and carries on, no longer sends a challenge that contradicts its answer.
- A failed email sign-in's DEBUG log names the identity and credential ids, no longer the email address. `SignInFailed` still carries the address for your own handling.

### Documentation

- Every example with a browser sign-in (web-app, spa, web-and-api, observability) lists where you're signed in and lets you sign any browser out; the observability dashboard shows purged session records.
- The Sessions guide's "Sign out everywhere" recipe now uses `WhoSessions.endAll`, which covers every session store.
- The examples make keys only after a recent sign-in: web-and-api's Keys page sends a stale session to sign in again and back, the API example answers a stale token with the step-up challenge, and the observability example's page says to sign in again.

- A new example, `who-examples/observability`: who's meters, traces and logs in Grafana's LGTM
  stack (`grafana/otel-lgtm`), with a provisioned dashboard, a traffic generator that makes real
  requests, and a page whose every request links to its trace and log lines in Grafana.

## [0.18.0] - 2026-11-16

### Breaking changes

- `who-spring-boot-starter` brings the base only: `who-core`, `who-spring-security`, `who-jdbc`, `who-rbac` and the autoconfiguration. Add `who-email-link`, `who-passkey`, `who-apikey`, `who-jwt` or `who-enrollment` for each feature you use. See [Upgrading to 0.18.0](docs/guides/upgrading.md#upgrading-to-0180).
- A module on the classpath is on. `who.email-link.link-base-url` and `who.passkey.rp-id` no longer turn their method on; a module whose required settings are missing fails startup instead of staying off. Turn a module off with `who.<module>.enabled=false`.
- An application with no `SecurityFilterChain` gets who's default chain instead of Spring Boot's form login and HTTP Basic: every sign-in method on the classpath, `401` for signed-out requests, and only `/error`, the sign-in endpoints, Actuator's health endpoint and `who.security.public-paths` open. An application with its own chain is unaffected.
- The `jsonOneTimeTokenAuthenticationConverter` bean is gone; the converter is built with the `EmailLinkLoginMethod` bean.
- `who-jwt` fails startup when Spring Boot builds the decoder and `spring.security.oauth2.resourceserver.jwt.audiences` is not set, since a token minted for any other application of the same issuer would otherwise sign in.
- `JwtCredential`, `JwtCredentialRepository`, `JdbcJwtCredentialRepository` and `JwtCredentialService` are `FederatedCredential`, `FederatedCredentialRepository`, `JdbcFederatedCredentialRepository` and `FederatedCredentialService` in the new `who-federated` module (package `org.jwcarman.who.federated`), which `who-jwt` brings.
- The table `who_jwt_credential` is renamed `who_federated_credential`. The bundled schema script and the Liquibase changelog rename it when the old table exists; Flyway users add the `ALTER TABLE`. No rolling upgrade.
- `CredentialKind.JWT` is `CredentialKind.FEDERATED`, and the metrics' `kind` tag is `federated`.
- `who.passkey.registration-window` is replaced by `who.security.recent-sign-in`.
- `EmailLinkSender.send` takes a sealed `WhoEmail` (`EmailLinkMessage`, `EmailAddressConfirmationMessage`, `EmailAddressChangedMessage`); custom senders switch over it.
- `EmailAddressTakenException` is `@ResponseStatus(CONFLICT)`.
- API keys need a lifetime and scopes: `ApiKeyService.create(identity, name, lifetime, scopes)` answers `NewApiKey`. A key signs in with its scopes intersected with its owner's current permissions. `ApiKeyService` and `ApiKeyAuthenticationProvider` take new constructor arguments, and `ApiKeyCredentialRepository` has two new methods, `touch` and `expireBy`. See [Upgrading to 0.18.0](docs/guides/upgrading.md#upgrading-to-0180).
- `who_api_key_credential` gains `created_at`, `expires_at` and `last_used_at`, and the new table `who_api_key_scope` holds scopes. The Liquibase changelog deletes existing keys first; issue new ones.

### Added

- `WhoSecurity.stateless()` mounts who's token and key sign-in, and no browser method, into a chain of its own: no session (the request's session is hidden from the whole chain, so a session cookie signs nothing in and is neither given a new id nor ended, and a signed-out call starts none; application code there that asks for a session gets `IllegalStateException`), no logout, no CSRF, and who's `401` and `403`. An app that is both a website and an API gives each its own chain. Startup fails when neither `who-jwt` nor `who-apikey` is on. It also fails when the chain's sessions are turned back on afterwards with `sessionManagement(...)` or `securityContext(...)`, since that would sign requests in from a session with CSRF off.
- `StatelessLoginMethod.challenge()`: the scheme a signed-out `401` names in `WWW-Authenticate`; `Bearer` for JWTs.
- Production-ready API keys: every key expires (`who.api-key.max-lifetime`, default 365 days), holds only the scopes it was made with, and records its last use (written at most once per `who.api-key.last-used-interval`, default 5 minutes). `findByIdentity` lists an identity's keys with no secret in them, and `rotate` replaces a key while the old one keeps working for an overlap of up to 7 days. Keys carry a configurable prefix (`who.api-key.prefix`, default `who_`) and a checksum, so secret scanners can find them, and a malformed key is refused without a database lookup. An expired key fails with the new `SignInFailureReason.EXPIRED_KEY`. See [API Keys](docs/guides/api-keys.md).
- `who-oidc`: sign in with Google, GitHub or any OpenID Connect provider on Spring's `oauth2Login`, into a who session. The issuer always comes from configuration; an account who does not know becomes a pending sign-up that the person confirms (`GET /who/oidc/pending`, `POST /who/oidc/sign-up`), and a signed-in person links an account only by asking for it (`POST /who/oidc/link/{registrationId}`, from a browser session signed in within `who.security.recent-sign-in`, lasting as long; the provider is asked to show the person which account it is) and then confirming it (`POST /who/oidc/link`). A refused link is `SignInFailed` with the new reason `LINK_REFUSED`. A plain provider sign-in by someone already signed in never links: an unknown account lands on `error=signed-in`. Nothing links or signs in by email address. GitHub's primary verified address is read with the `user:email` scope. See [Social Login](docs/guides/social-login.md).
- Adding or changing an email address by confirming it: `POST /who/email/addresses` (`{ address, replaces? }`) sends the address a link and a code, and `POST /who/email/addresses/confirm` (`{ token }` or `{ code }`) adds it, by the same identity only. A change revokes the old address in the same transaction and tells it. The request answers alike whether the address is free or taken, and is held off per identity and per address by the resend interval. New: `EmailAddressChanges`, `EmailAddressConfirmationRequested`, `EmailCredentialNotFoundException`, `InvalidEmailAddressConfirmationException`, the `who_email_address_request` table, and `who.email-link.address-confirmation-url`, `address-confirmation-subject` and `address-changed-subject`. See [Email Links](docs/guides/email-links.md#adding-or-changing-an-address).
- A provider account who does not know lands on `failure-url?pending=sign-up`, and a link the person asked for on `who.oidc.link-url?pending=link` (default `success-url`), with `error=` kept for what failed.
- `who.security.recent-sign-in`, one window for adding any credential: passkeys, provider links and email addresses.
- `WhoSignUpPolicy`, `SignUpRequest` and `SignUpDecision` in who-core, and `ClosedSignUpPolicy`, who's default, which refuses every sign-up.
- `who.oidc.*` settings: `enabled`, `success-url`, `failure-url`, `link-url`, `sign-up-window`, and `providers.<id>.enabled`, `issuer`, `subject-attribute`, `trust-email`.
- `SignInMethod.OIDC`; `SignInFailureReason.SIGN_UP_REFUSED` and `PROVIDER_ERROR`.
- The `who_last_sign_in` cookie, naming the method of the last browser sign-in for a "Last used" badge; `who.security.last-sign-in-cookie.enabled=false` turns it off.
- `StubOidcProvider` in `who-test`, an OpenID Connect (or GitHub-like) provider for integration tests.
- A native-image test app for provider sign-in, and serialization hints for the pending sign-up.
- `who-examples/web-and-api`, a website and an API in one app, each with its own `SecurityFilterChain`: sessions, CSRF, email links and Keycloak for the pages; `WhoSecurity.stateless()` with Keycloak access tokens and API keys for `/api/**`. People make, list and revoke their keys on the website; a person is one identity on both.
- `who-examples/api`, the board as a stateless JSON API on who's default chain: Keycloak access tokens (device flow, checked audience) for accounts the app links, and API keys that people make, list and revoke through it with a token; a key reads only itself, at `/api/keys/self`. It replaces `who-example`, which is removed.
- `who-examples/spa`, the same app as a React and TypeScript single-page app over a JSON backend: Maven builds it into the jar, or Vite's dev server runs it with the `vite` profile.
- `POST /who/oidc/link/{registrationId}` answers `200 {"redirectUrl": ...}` instead of a redirect when the request accepts JSON and not HTML, so a single-page app can start a link with `fetch` and the CSRF header.
- `who-examples/web-app`, a runnable server-rendered app on who's default chain: email links and codes, passkeys, Keycloak sign-up and linking, Google and GitHub behind profiles, and `@RequiresPermission` pages. Spring Boot's Docker Compose support starts its Postgres, Mailpit and Keycloak.
- `who.email-link.enabled`, `who.passkey.enabled`, `who.api-key.enabled`, `who.jwt.enabled` and `who.enrollment.enabled`, each `true` by default.
- `WhoSecurity.all()`, which mounts every sign-in method on the classpath into a chain of your own, and `WhoPublicPaths` with the paths a signed-out request must reach. Each module contributes a `PasswordlessLoginMethod` or `StatelessLoginMethod` bean.
- `who.security.public-paths`, the application's own paths open to signed-out requests.
- A startup failure report for missing or unusable who settings, naming the property, the fix and the switch that turns the module off (`WhoConfigurationException`).

### Changed

- With no browser sign-in method, `WhoSecurity.all()` (and so who's default chain) answers a call without a token with `WWW-Authenticate: Bearer` on its `401` when JWTs are on, before handing it to your exception handling; the API example's own header is gone.
- The "passkey added" startup warning is logged by `org.jwcarman.who.autoconfigure.WhoPasskeyAutoConfiguration`.
- who's autoconfiguration is split into one class per module (`WhoAutoConfiguration`, `WhoSpringSecurityAutoConfiguration`, `WhoEmailLinkAutoConfiguration`, `WhoPasskeyAutoConfiguration`, `WhoApiKeyAutoConfiguration`, `WhoJwtAutoConfiguration`, `WhoEnrollmentAutoConfiguration`, `WhoWebSecurityAutoConfiguration`), so `@ImportAutoConfiguration` and exclusions can name one module.
- The native test apps use the starter and who's default chain, with no security configuration of their own.
- When email links have no `EmailLinkSender` and Spring's mail support is not on the classpath, the startup failure says to add `spring-boot-starter-mail` (who-email-link does not bring it).

### Fixed

- An application with email links and its own `oauth2ResourceServer` refused every bearer token: Spring's resource server took who's one-time-token converter bean as its own and read each request as a sign-in code.

### Documentation

- The Social login guide says to confirm a provider sign-up only when the person clicks, never as soon as the page sees `pending=sign-up`: the confirmation is where the person sees which account they are signing up with, and confirming by itself lets a login CSRF at the provider sign them up to an attacker's account.

## [0.17.0] - 2026-11-14

### Breaking changes

- An API key that is unknown, revoked, or belongs to an inactive identity gets the uniform `401` at once, even on endpoints that permit anonymous access. Before, the request continued as anonymous. No header, or a blank header, behaves as before.
- API keys sign in through Spring's authentication manager. A database fault during the key check surfaces as the original `RuntimeException` rather than a `ServletException` (still a `500`), an anonymous HTTP session's id is rotated when a valid key signs in, and a filter passed to `WhoApiKeySecurity.apiKey(filter)` has its manager and failure handler set by who.

### Added

- Public security events, published as Spring application events after the surrounding transaction commits: `SignInSucceeded`, `SignInFailed` (with a server-side reason that never reaches the client), `SignedOut`, `SessionEnded`, `SignInEmailRequested`, `PasskeyRegistrationRefused`, `IdentityCreated`, `IdentityDeactivated`, `IdentityReactivated`, `IdentityDeleted`, `OrphanedCredentialsPurged`, `CredentialAdded`, `CredentialRevoked`, `EnrollmentTokenCreated`, `EnrollmentTokenRevoked`, `RoleCreated`, `RoleDeleted`, `RolePermissionAdded`, `RolePermissionRemoved`, `RoleAssigned` and `RoleUnassigned`. A listener that throws is logged and never changes who's answer.
- `WhoEvent` and `WhoEventPublisher` in `who-core`; services take a publisher, and their existing constructors keep publishing nothing.
- `JwtCredentialService` with `link` and `unlink`, so linking JWT credentials publishes events.
- `WhoService.resolveDetailed`, which says why a credential did not resolve (not linked, no such identity, inactive). `Resolution` may gain outcomes in a minor release, so a `switch` over it needs a default branch; a `null` answer, such as from a mock, is treated as not linked.
- One DEBUG log line for each failed sign-in, with its reason.
- A native test bed in who's own build: `who-native-tests` has one small app per sign-in method (email link and code, passkey, API key, JWT), each depending only on the who modules that method needs, and each compiled as a GraalVM native image and driven over HTTP against PostgreSQL, events included. The Native workflow runs them on `main` and weekly. The apps are never published.
- Micrometer meters built from who's events, registered when the application has a `MeterRegistry` (for example with Actuator): `who.sign-ins{method, outcome, reason}`, `who.sign-in.emails{outcome}`, `who.sign-outs`, `who.sessions.ended{reason}`, `who.credentials.added{kind}` and `who.credentials.revoked{kind}`. Tag values come only from who's enums; no address or id is a tag.
- Micrometer observations `who.principal.resolve` (every credential resolution, so every authenticated request) and `who.sign-in.email.issue` (looking up the address, storing the code and sending the mail), each tagged with its `outcome`. A failing observation handler is logged and never changes who's answer.
- `WhoService` and `EmailLinkIssuer` constructors that take an `ObservationRegistry`; the existing constructors observe nothing.

### Changed

- who's sign-in and session code calls `WhoService.resolveDetailed` instead of `resolve`; application tests that mock `WhoService` should stub it, because a mock that stubs only `resolve` answers `null` from `resolveDetailed`, which who treats as not linked.
- New default methods keep existing SPI implementations compiling: `CredentialRemover.find`, `IdentityRepository.changeStatus`, `ApiKeyCredentialRepository.findById`, `JwtCredentialRepository.findById`, `EnrollmentTokenRepository.revokePendingForIdentity`, and `RoleRepository.deleteByIdIfPresent`, and the RBAC `...IfAbsent` and `...IfPresent` methods. An implementation that keeps the defaults publishes events with a `null` label, and no `EnrollmentTokenRevoked` for a replaced token.
- `SignInSucceeded` is published only for email link, email code and passkey sign-ins, by who's JSON success handler; API-key and JWT requests authenticate every request and publish only `SignInFailed`. An application that defines its own `JsonAuthenticationHandlers` with the old two-argument constructor publishes none.
- `WhoEnrollmentService` stamps new tokens (`createdAt`, `expiresAt`) and events from its `Clock`, through the new `EnrollmentToken.create(identity, expiration, now)`. The two-argument `create` is unchanged and still uses `Instant.now()`.
- `PasskeyCredential` gained a `passkeyLabel` component, so record deconstruction patterns on it need the second component and equality now includes the label. The one-argument constructor remains.
- `WhoPasskeyAuthenticationProvider` has a new constructor `(rpOperations, credentialRepository, whoService, WhoEventPublisher, Clock)` that publishes `SignInFailed` stamped from the clock; the existing three-argument constructor keeps publishing nothing.
- Event records may gain components in minor releases: read fields through accessors, and do not construct who's events or take them apart with record patterns.
- `RbacService` is a `@Service`: a component scan builds one without a publisher, which publishes no role events. Use the autoconfigured bean.
- Deleting a role publishes `RoleDeleted` once, even when two deletes race.

### Fixed

- `who.initialize-schema: always` created no tables in a native image: the initializer finds each module's schema script by looking up a class from that module by name, which a native image answers only for registered types. who-autoconfigure now registers them (`WhoSchemaRuntimeHints`). Liquibase was not affected.

### Documentation

- An [Events](https://jwcarman.github.io/who/guides/events/) guide: every event, when listeners run, personal data, reading the actor and client, and the compatibility policy.
- The security model guide gains an audit trail section and the logging rule.
- An upgrading section for 0.17.0.
- An [Observability](https://jwcarman.github.io/who/guides/observability/) guide: every meter and tag value, the observations, and example alerts.
- Native Images: properties that switch who's features on (`who.email-link.link-base-url`, `who.passkey.rp-id`) must be present when the image is built, because Spring AOT evaluates conditions at build time; set them in `application.yml` and override the value at run time.

### Requirements

- `who-core` depends on `io.micrometer:micrometer-observation` (managed by Spring Boot's dependency management).

## [0.16.0] - 2026-11-13

### Breaking changes

- `WhoAuthenticationToken` and `SignInFailedException` are `final`. Both called an inherited method from their constructor, which a subclass could observe half-built.

### Added

- Every artifact on Maven Central ships a CycloneDX SBOM (`-cyclonedx.json` and `-cyclonedx.xml`).
- Release jars carry a GitHub build provenance attestation: `gh attestation verify <jar> --repo jwcarman/who`.
- Builds are reproducible: rebuilding a release tag gives byte-for-byte the jars on Maven Central. `SECURITY.md` shows how to check, and how to verify the GPG signature.

### Fixed

- Sign-in emails keep the request's trace and logging context. who's email executor applies the application's `TaskDecorator` beans, as Spring Boot does to its own executors, so with a `ContextPropagatingTaskDecorator` the send appears in the sign-in request's trace.
- Passkey sign-ins are observed. With an `ObservationRegistry` (Spring Boot Actuator), Spring Security records a `spring.security.authentications` observation for each sign-in; passkey sign-ins were missing, because who builds that authentication manager itself.
- A typed sign-in code containing non-ASCII letters is rejected. Some upper-cased to ASCII before the check (the dotless `ı` to `I`, the long `ſ` to `S`) and one turned into two letters (the ligature `ﬀ` to `FF`), so `Kjı2Kj` was read as `KJ12KJ`. Each such attempt still counted against the attempt limit, so guessing got no easier. Found by fuzzing.

### Documentation

- Fuzz tests for the sign-in request bodies, typed codes and the API-key header, replayed in every build and fuzzed weekly by a new workflow. CONTRIBUTING explains how to run them.
- A [Security Model](https://jwcarman.github.io/who/guides/security-model/) guide: what who protects, what the application must do, and what who assumes.
- `SECURITY.md` (private vulnerability reporting), `CONTRIBUTING.md`, `GOVERNANCE.md` and a code of conduct.
- The security model guide adds trust boundaries, the design principles who follows, and the common weaknesses it counters.

## [0.15.0] - 2026-11-13

### Breaking changes

- Every sign-in email carries a typed code as well as the link. `POST /login/ott` accepts `{"token": "..."}` or `{"email": "...", "code": "..."}`. Using one ends the other.
- `EmailLinkSender.send` takes an `EmailLinkMessage(address, link, code, expiresAt)` instead of `(address, link)`.
- `who.email-link.code-secret` is required when email-link sign-in is on, at least 32 characters.
- `who_email_link_token` gained `code_hash` and `code_attempts` (both `NOT NULL`). Liquibase applies it with the new changeSet `who-email-link-token-2`; other setups run the SQL in the upgrading guide. Outstanding sign-in links are dropped.
- `EmailLinkTokenRepository`, `EmailLinkAuthenticationProvider` and `WhoOneTimeTokenService` have changed signatures.
- The default subject is `Your sign-in link and code`.

### Added

- The code uses the Crockford base32 alphabet, is shown grouped (`K7F-2QM`), and is read without regard to case, spaces or dashes. `O` reads as `0`, and `I` and `L` as `1`.
- After `who.email-link.code-max-attempts` wrong codes (default 5) the code is burnt; the link still works.
- `who.email-link.code-length` (default 6, at least 6), `who.email-link.code-max-attempts` and `who.email-link.code-secret`.
- `EmailLinkMessage.formattedCode()`.
- The default email ends with "If you didn't ask to sign in, ignore this email. Never share this code with anyone."

### Changed

- Stricter input handling: a code sign-in whose address is longer than 320 characters or contains a control character fails with a 401 without reaching the database, `/ott/generate` treats such an address as unknown, a typed code longer than 64 characters is rejected, and `/ott/generate` and `/login/ott` ignore a JSON body larger than 8192 bytes.
- `EmailLinkSettings` itself rejects a `codeSecret` shorter than 32 characters.
- Startup fails when `who.email-link.code-secret` is an unresolved placeholder (`${...}`), which Spring would otherwise bind as literal text and use as the key.

## [0.14.0] - 2026-11-10

### Breaking changes

- who no longer writes error responses. It throws exceptions and hands those raised in Spring Security's filters to the application's Spring MVC exception resolvers, so the application's `@RestControllerAdvice` formats every error. New exceptions in `org.jwcarman.who.spring.security.web`:
  - `SignInFailedException` (401), with the fixed message `"Sign-in failed"`;
  - `NotSignedInException` (401);
  - `ReauthenticationRequiredException` (401);
  - `InvalidPasskeyRegistrationException` (400).
- The `{"error":"invalid_token"}`, `{"error":"invalid_credential"}`, `{"error":"reauthentication_required"}` and `{"error":"invalid_registration"}` bodies are gone. Without an application mapping, who calls `sendError` with the status above.
- `JsonAuthenticationHandlers` takes `(JsonMapper, WhoExceptionHandoff)`, and `failure(String)` is removed.
- `PasskeyRegistrationGuardFilter` takes a `WhoExceptionHandoff` instead of a `JsonMapper`.
- `GET /who/me` throws `NotSignedInException` when signed out.
- `WhoWebSecurity.passwordless()` installs an entry point and an access-denied handler that hand the exception off, instead of a bare 401 and Spring's empty 403.
- Errors raised in the filters now reach the application's advice. A catch-all `@ExceptionHandler(Exception.class)` would answer them with its own status (often 500): map who's exceptions before upgrading. Only a global advice sees them, not one limited by `basePackages`, `assignableTypes` or `annotations`.

### Added

- `WhoExceptionHandoff`, with its `Resolver` interface. who's autoconfiguration builds one over the application's `handlerExceptionResolver` bean.
- The [Errors](docs/guides/errors.md) guide.

### Changed

- The release build uses central-publishing-maven-plugin 0.12.0, which Maven 3.10 needs; earlier versions produce bundles Maven Central rejects.

## [0.13.0] - 2026-11-08

### Added

- `WhoService.deactivate(identityId)` and `reactivate(identityId)` take an identity out of service
  and put it back. A deactivated identity keeps its credentials, roles and history, but no credential
  resolves to it: its next request is unauthenticated, its sign-in links are refused
  (`INACTIVE_IDENTITY`) and its passkeys no longer sign in. Both return `false` for an unknown
  identity and change nothing when the identity is already in that state.
- `WhoMockMvcBrowser.performWithoutCsrf(request)` in `who-test` sends a request as a forged
  cross-site request would look: the browser's session and cookies, but no `X-XSRF-TOKEN` header and
  no token pre-fetch. Use it to prove an endpoint rejects unsafe requests that lack the CSRF header.

## [0.12.0] - 2026-11-07

### Breaking changes

- `WhoWebSecurity.passwordless()` uses `NullRequestCache`: a request denied with `401` is no longer
  saved in the session, so there is no post-login redirect to the saved request.
- `EmailLinkIssuer.issue` returns an `EmailLinkOutcome` instead of `void`. Source still compiles, but
  code built against 0.11 must be recompiled, and an override must change its return type.
  `/ott/generate` still answers `202` at once and ignores the outcome.
- `RoleRepository.findAll()` and `IdentityRoleRepository.findIdentityIdsByRoleId(UUID)` are new
  abstract methods, so an app with its own implementation of either interface must implement them.

### Added

- `who-bom`: a bill of materials that aligns the versions of every published who module. Import it,
  then declare `who-spring-boot-starter` and `who-test` without versions. It manages only who's own
  artifacts, not Spring Boot's.
- The session holds the identity id under Spring Session's principal-name index attribute, so
  `FindByIndexNameSessionRepository.findByPrincipalName(identityId.toString())` finds every session of
  an identity ("sign out everywhere"). who needs no Spring Session dependency.
- `RbacService.findRole(String)` and `findRole(Enum)` return an `Optional<Role>`; `listRoles()`
  returns every role sorted by name; `findIdentityIdsWithRole(Role)` returns the ids of the
  identities that hold a role.
- `EmailLinkOutcome` (`SENT`, `UNKNOWN_ADDRESS`, `INACTIVE_IDENTITY`, `RESEND_SUPPRESSED`,
  `SEND_FAILED`) tells a caller of `EmailLinkIssuer.issue` whether the email went out.
- `who-test` (test scope): `WhoTestSessions.signedIn(identityId, credentialId)` returns a
  `MockHttpSession` that who resolves from the database on the next request, and `WhoMockMvcBrowser`
  wraps a `MockMvc` with one session and its cookies, echoing the `XSRF-TOKEN` cookie into
  `X-XSRF-TOKEN` on unsafe requests as an SPA does with `csrf.spa()`. With Spring Session,
  `signedInAs` saves the session in the app's `SessionRepository` and holds its cookie. Overloads
  take the sign-in `Instant`, for apps with a fixed `Clock`.
- Native serialization hints for sessions (Spring Session JDBC: `WhoSession` and what it holds) and
  for passkey ceremony options, so apps need no native configuration of their own.

### Changed

- Creating a role whose name is taken throws `RoleAlreadyExistsException` (still an
  `IllegalArgumentException`) with a message that does not name the role.

### Documentation

- New [Sessions](https://jwcarman.github.io/who/guides/sessions/) and
  [Testing](https://jwcarman.github.io/who/guides/testing/) guides; the Upgrading, Native Images,
  RBAC and Email Links guides cover the 0.12 changes.
- The Passkeys guide no longer says `anyRequest().authenticated()` protects
  `POST /webauthn/register/options`: Spring runs that filter before the authorization filter, and
  who's `PasskeyRegistrationGuardFilter` protects it.

## [0.11.0] - 2026-11-07

Read [Upgrading](https://jwcarman.github.io/who/guides/upgrading/) before you upgrade. Existing
databases must convert their timestamp columns first.

### Breaking changes

- Every timestamp column is `TIMESTAMP WITH TIME ZONE`, and repositories bind `OffsetDateTime` in
  UTC. Databases created by 0.10.0 or earlier need `ALTER TABLE` statements (in the upgrade guide)
  before the upgrade, or reading timestamps may fail.
- The bundled schema files are renamed to `who-<module>-schema.sql`:
  `org/jwcarman/who/jdbc/who-jdbc-schema.sql`, `rbac/who-rbac-schema.sql`, `jwt/who-jwt-schema.sql`,
  `enrollment/who-enrollment-schema.sql`, `apikey/who-apikey-schema.sql` and
  `emaillink/who-email-link-schema.sql`. Update any `spring.sql.init.schema-locations` that names them.
- Liquibase users who copied the old YAML changelog should switch to including
  `classpath:org/jwcarman/who/db/who-changelog.xml`, on a fresh schema or after `changelog-sync`.

### Added

- `who-email-link`: sign-in by an emailed link, on Spring Security's one-time-token login.
  `EmailCredentialService` (`register`, `findByAddress`, `revoke`), the `EmailLinkSender` SPI, and
  `MailEmailLinkSender` for `JavaMailSender`. It is opt-in: set `who.email-link.link-base-url`.
- `who.email-link.token-ttl`, `resend-interval`, `from` and `subject` properties.
- `WhoWebSecurity.passwordless()`: session sign-in for a browser chain, with `csrf.spa()`, a plain
  401 for unauthenticated requests, and `/logout` answering 204. It fails at startup if the chain
  disables CSRF or is stateless.
- `GET /who/me` returns `{"identityId", "permissions"}` for the signed-in caller.
- Liquibase changelogs for every module, and `who-changelog.xml` in `who-autoconfigure`.
- The [Email Links](https://jwcarman.github.io/who/guides/email-links/) guide.
- `who-passkey`: sign-in with passkeys, on Spring Security's WebAuthn support and its default
  endpoints. `PasskeyService` (`list`, `revoke`), the `PasskeyAddedNotifier` SPI and
  `MailPasskeyAddedNotifier`. It is opt-in: set `who.passkey.rp-id`. Storage is PostgreSQL only
  (tables `who_passkey_user` and `who_passkey_credential`).
- `who.passkey.rp-id`, `rp-name`, `allowed-origins`, `registration-window` and `from` properties.
- The [Passkeys](https://jwcarman.github.io/who/guides/passkeys/) guide.
- Native image support with no app configuration. Every module ships GraalVM reachability
  metadata for its Liquibase changelogs and schema script, and `who-passkey` registers Spring AOT
  hints for the Spring Security WebAuthn and webauthn4j type families.
- The [Native Images](https://jwcarman.github.io/who/guides/native/) guide.
- `WhoService.deleteIdentity(UUID)`: deletes an identity with its email, JWT and API key
  credentials and their links, in one transaction. Deleting `who_identity` directly left those
  credential rows behind.
- `WhoService.purgeOrphanedCredentials()`: deletes credentials linked to no identity, such as those
  left by a direct delete. It also deletes JWT credentials saved for an enrollment not yet redeemed.
- The `CredentialRemover` SPI. The JDBC email, JWT and API key repositories implement it, and the
  autoconfiguration passes every `CredentialRemover` bean to `WhoService`.

### Changed

- `EmailCredentialService.register` accepts an address that is registered but linked to no
  identity: it deletes that orphaned credential (and any outstanding sign-in link) and registers
  a new one for the identity. It returns the existing credential when the address
  is already the identity's own. It still throws `EmailAddressTakenException` when another identity
  holds the address.
- `CredentialIdentityRepository` has a new method, `findCredentialIdsByIdentityId(UUID)`. Custom
  implementations must add it.
- The autoconfiguration declares the email, JWT and API key repository beans with their JDBC
  types. They are still replaced by any bean of the repository interface.
- `WhoAuthenticationToken.getName()` returns the identity id, for every chain (sessions, JWTs and
  API keys). Spring's WebAuthn support keys on it. Anything that reads `authentication.name`, such
  as SpEL expressions or audit logs, now sees the identity id.
- The session keeps only the identity and credential ids. Who re-resolves the principal on every
  request, so role changes and deactivation take effect on the next request.
- The Liquibase guide now includes `who-changelog.xml` instead of a copied changelog.
- Who's JDBC repositories are no longer annotated `@Repository`, so no exception-translation proxy
  is created for them (which Spring AOT could not generate natively). Exceptions are still
  translated: `JdbcClient` throws Spring's `DataAccessException` hierarchy. An app that component-scans
  `org.jwcarman.who` no longer picks these classes up; who's autoconfiguration creates them.
- With `who.initialize-schema: never`, who no longer looks up its schema scripts.

### Fixed

- Deleting an identity left its email, JWT and API key credentials behind, and an orphaned email
  address could never be registered again. Use `WhoService.deleteIdentity`; registration now
  reclaims an orphaned address, and `purgeOrphanedCredentials()` cleans up old data.
- A native image could not start: who's changelogs and schema files were missing from the image,
  and who's `@Repository` beans needed proxies Spring AOT could not generate.

### Requirements

- Spring Boot 4.1 (built against 4.1.2). Passkeys use `spring-security-webauthn` with
  `webauthn4j-core` 0.32.0.RELEASE, pinned by who.
- Java 25+ and PostgreSQL 14+, unchanged.

## [0.10.0] - 2026-11-07

A security release. Read [Upgrading to 0.10.0](https://jwcarman.github.io/who/guides/upgrading/)
before you upgrade.

### Breaking changes

- `ApiKeyAuthenticationFilter` is no longer registered with the servlet container. Add it to
  each chain that accepts API keys: `http.with(WhoApiKeySecurity.apiKey(), Customizer.withDefaults())`.
- Method security is on by default, so `@RequiresPermission` is enforced with no setup. Set
  `who.method-security.enabled=false` to turn it off.
- `ApiKeyCredentialRepository.deleteById` returns `boolean`.
- `who_identity_role.identity_id` references `who_identity(id)` with `ON DELETE CASCADE`.
  Existing databases need the foreign key added by hand (SQL in the upgrade guide).
- Pending enrollment tokens issued before 0.10.0 are predictable. Revoke them and issue new ones
  (SQL in the upgrade guide).

### Added

- `Secrets.token()`: 256 random bits, URL-safe Base64. The one source of secret tokens.
- `ApiKeyService.revoke(UUID)` deletes a key and its identity link.
- `EnrollmentIdentityInactiveException` and `CredentialAlreadyLinkedException`.
- `who.method-security.enabled` (default `true`).

### Fixed

- Enrollment token values were predictable: they followed from the token's UUIDv7 id. They now
  come from `Secrets.token()`.
- An enrollment token could be redeemed twice by concurrent requests. Redemption is now one
  atomic conditional update, and a failed link rolls it back.
- `enroll` accepted inactive identities and credentials already linked to another identity.
- The API-key filter ran on every request, outside Spring Security's chains.
- A JWT with no `iss` or no `sub` gave 500. It now gives 401.
- `EnrollmentTokenNotFoundException` and `EnrollmentToken.toString()` exposed token values.
- Revoking a redeemed token cleared its redemption time.
- Deleting an identity left its role assignments behind.

### Documentation

- A documentation site at [jwcarman.github.io/who](https://jwcarman.github.io/who/), with guides
  and per-release API docs. The README is now a short overview.
- The `PermissionsResolver` Javadoc now says Who uses exactly one resolver.

### Requirements

- Spring Boot 4.1 (built against 4.1.2).
- Java 25+ and PostgreSQL 14+, unchanged.

[Unreleased]: https://github.com/jwcarman/who/compare/0.22.0...HEAD
[0.22.0]: https://github.com/jwcarman/who/releases/tag/0.22.0
[0.21.0]: https://github.com/jwcarman/who/releases/tag/0.21.0
[0.20.0]: https://github.com/jwcarman/who/releases/tag/0.20.0
[0.19.0]: https://github.com/jwcarman/who/releases/tag/0.19.0
[0.18.0]: https://github.com/jwcarman/who/releases/tag/0.18.0
[0.17.0]: https://github.com/jwcarman/who/releases/tag/0.17.0
[0.16.0]: https://github.com/jwcarman/who/releases/tag/0.16.0
[0.15.0]: https://github.com/jwcarman/who/releases/tag/0.15.0
[0.14.0]: https://github.com/jwcarman/who/releases/tag/0.14.0
[0.13.0]: https://github.com/jwcarman/who/releases/tag/0.13.0
[0.12.0]: https://github.com/jwcarman/who/releases/tag/0.12.0
[0.11.0]: https://github.com/jwcarman/who/releases/tag/0.11.0
[0.10.0]: https://github.com/jwcarman/who/releases/tag/0.10.0
