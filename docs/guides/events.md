# Events

who publishes an event for every sign-in (success and failure), sign-out, ended session, sign-in
email, and every change to identities, credentials, enrollment tokens and roles. Use them for an
audit log, alerts and metrics. who does not store events; your application decides what to keep.

Events are ordinary Spring application events. Listen with `@EventListener`:

```java
@Component
class AuditLog {

  private static final Logger log = LoggerFactory.getLogger(AuditLog.class);

  @EventListener
  void on(SignInFailed event) {
    log.info("sign-in failed: method={} reason={} identity={}",
        event.method(), event.reason(), event.identityId());
  }

  @EventListener
  void on(CredentialAdded event) {
    log.info("credential added: identity={} kind={} credential={}",
        event.identityId(), event.kind(), event.credentialId());
  }

  @EventListener
  void on(WhoEvent event) {   // receives every event, including the two above
    // keep it to counts and metrics: logging the event here would record the two above twice
    log.debug("who event: {}", event.getClass().getSimpleName());
  }
}
```

Every event is a Java record that implements `WhoEvent`, whose `occurredAt()` says when it
happened. Events live in the module that publishes them. A module's events are published only when
that module is on the classpath.

## The events

All events have `occurredAt`. Fields marked "or null" can be null; every other field is set.
`identityId`, `credentialId`, `tokenId` and `roleId` are UUIDs.

### Sign-in and sessions

| Event | Module | Fires when | Fields |
|---|---|---|---|
| `SignInSucceeded` | who-core | A sign-in is complete. Email link and code, and passkey: after the session is saved. API keys and JWTs: on every request that carries a valid key or token. | `method`, `identityId`, `credentialId` |
| `SignInFailed` | who-core | A sign-in attempt fails. The client still gets the same `401`. | `method`, `reason`, `address` or null, `identityId` or null, `credentialId` or null |
| `SignedOut` | who-core | A caller signs out of a who session. | `identityId`, `credentialId` |
| `SessionEnded` | who-core | who drops a session because its identity is no longer active or its credential was revoked. | `identityId`, `credentialId`, `reason` |
| `SignInEmailRequested` | who-email-link | A request to `POST /ott/generate` has been handled, once per request. | `address` or null, `outcome`, `identityId` or null |
| `PasskeyRegistrationRefused` | who-passkey | A signed-in identity tries to add a passkey, but signed in longer ago than the registration window allows. | `identityId`, `reason` |

`SignInMethod`: `EMAIL_LINK`, `EMAIL_CODE`, `PASSKEY`, `API_KEY`, `JWT`.

`SignInFailureReason`, as fine as each path can tell without an extra database call:

| Method | Reasons |
|---|---|
| `EMAIL_LINK` | `MALFORMED`, `INVALID_LINK` (unknown, used or expired), `INACTIVE_IDENTITY` |
| `EMAIL_CODE` | `MALFORMED`, `NO_LIVE_CODE` (unknown address, no code, expired, or attempts used up), `WRONG_CODE`, `CODE_ALREADY_USED` (the right code lost a race with another use or expiry), `INACTIVE_IDENTITY` |
| `PASSKEY` | `INVALID_ASSERTION` (signature, origin or unknown credential; they cannot be told apart), `INACTIVE_IDENTITY` |
| `API_KEY` | `UNKNOWN_KEY` (unknown, revoked, or linked to no identity), `INACTIVE_IDENTITY` |
| `JWT` | `MISSING_CLAIMS`, `UNKNOWN_CREDENTIAL`, `INACTIVE_IDENTITY` |

A JWT that Spring rejects before who sees it (bad signature, expired) publishes no who event;
Spring publishes its own. The same goes for a malformed passkey assertion that Spring rejects
before who's provider runs: no `SignInFailed`.

`SessionEndReason`: `IDENTITY_INACTIVE` when the session's credential now resolves to another
identity, or its identity is inactive; `CREDENTIAL_REVOKED` when the credential is no longer
linked, or its identity was deleted.

`EmailLinkOutcome`: `SENT`, `UNKNOWN_ADDRESS`, `INACTIVE_IDENTITY`, `RESEND_SUPPRESSED`,
`SEND_FAILED`. `SignInEmailRequested` is published on the email thread, after the caller has
been answered with `202`.

`PasskeyRegistrationRefusedReason`: `REAUTHENTICATION_REQUIRED`.

### Identities and credentials

| Event | Module | Fires when | Fields |
|---|---|---|---|
| `IdentityCreated` | who-core | `WhoService.createIdentity` | `identityId` |
| `IdentityDeactivated` | who-core | An active identity is deactivated. Nothing is published if it already was. | `identityId` |
| `IdentityReactivated` | who-core | An inactive identity is reactivated. | `identityId` |
| `IdentityDeleted` | who-core | `WhoService.deleteIdentity` | `identityId` |
| `OrphanedCredentialsPurged` | who-core | Credentials linked to no identity are purged, including when registering an email address replaces an orphaned credential (count 1). | `count` |
| `CredentialAdded` | who-core | A credential is linked to an identity: an email address registered, a passkey registered, an API key created, a JWT credential linked, or an enrollment completed. | `identityId`, `credentialId`, `kind`, `label` or null |
| `CredentialRevoked` | who-core | A credential is removed: an email address, passkey (including Spring's `DELETE /webauthn/register/{id}`) or API key revoked, a JWT credential unlinked, or an identity deleted (one event for each of its credentials). | `identityId`, `credentialId`, `kind`, `label` or null |

`CredentialKind`: `EMAIL`, `PASSKEY`, `API_KEY`, `JWT`, `OTHER`. The label is the email address,
the passkey's label, the API key's name, or the JWT issuer.

Events fire only on a real change. Two concurrent calls that deactivate the same identity publish
one `IdentityDeactivated`, and registering an email address the identity already owns publishes no
`CredentialAdded`.

Removals that cascade publish nothing of their own: the parent event implies them. Deleting a role
removes its permissions and assignments, and deleting an identity removes its role assignments and
enrollment tokens, with only `RoleDeleted` or `IdentityDeleted` (and a `CredentialRevoked` for each
credential of the identity).

To link JWT credentials so that events fire, use `JwtCredentialService.link` and `unlink`
([JWT](jwt.md#link-a-token-to-an-identity)). Saving through `JwtCredentialRepository`
directly publishes nothing. `unlink` takes a JWT credential id.

### Enrollment

| Event | Module | Fires when | Fields |
|---|---|---|---|
| `EnrollmentTokenCreated` | who-enrollment | `WhoEnrollmentService.createToken` | `identityId`, `tokenId` |
| `EnrollmentTokenRevoked` | who-enrollment | A token is revoked, by request or because a newer token for the identity replaced it. | `identityId`, `tokenId` |

The token's value is never in an event. Completing an enrollment publishes `CredentialAdded`.

### Roles

| Event | Module | Fires when | Fields |
|---|---|---|---|
| `RoleCreated` | who-rbac | A role is created. | `roleId`, `roleName` |
| `RoleDeleted` | who-rbac | A role is deleted. | `roleId`, `roleName` |
| `RolePermissionAdded` | who-rbac | A permission is granted to a role. | `roleId`, `roleName`, `permission` |
| `RolePermissionRemoved` | who-rbac | A permission is revoked from a role. | `roleId`, `roleName`, `permission` |
| `RoleAssigned` | who-rbac | A role is assigned to an identity. | `identityId`, `roleId`, `roleName` |
| `RoleUnassigned` | who-rbac | A role is removed from an identity. | `identityId`, `roleId`, `roleName` |

Like identity changes, these fire only when something changed.

## When listeners run

who publishes an event after the surrounding transaction commits. With no transaction (every
sign-in, for example), it publishes at once. A change that is rolled back publishes nothing.
So a listener always sees who's work as final, and it cannot roll that work back.

You do not need `@TransactionalEventListener`: a plain `@EventListener` already runs after
commit. Listeners run synchronously on the thread that made the change, which is the request
thread for sign-ins and admin calls. A sign-in's events are published after its response is
decided, so no listener can change the answer.

Two limits to know:

- **A listener that throws.** who catches a `RuntimeException` from publishing and logs a `WARN`,
  `A listener failed on {}: {}`, with the event's simple name and the exception's class name.
  The sign-in or change is not affected. Spring stops at the first listener that throws, so
  later listeners do not see that event. Handle your own failures inside the listener.
- **A listener that writes.** A listener for an event from a transactional who change runs right
  after the commit, while that transaction's resources are still bound to the thread. A listener
  that writes to a database or calls a who service should run in its own transaction
  (`@Transactional(propagation = Propagation.REQUIRES_NEW)`) or `@Async`. Without that, whether its
  writes are committed depends on the transaction manager. With plain JDBC they may happen to be
  committed as a side effect of the connection being released. Spring documents that, in an
  after-commit callback, changes made through the finished transaction's resources (a JPA
  `EntityManager`, for example) are not committed
  ([`TransactionSynchronization#afterCommit`](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/transaction/support/TransactionSynchronization.html#afterCommit())),
  and a who service call that joins with `REQUIRED` runs in the already-finished transaction.
- **An event published from another listener's after-commit callback** can be lost. This is the
  same limit `@TransactionalEventListener` has: the transaction is over by then. Do not make
  changes through who services from inside an after-commit callback and expect their events.

A database-backed audit listener, with its own transaction:

```java
@Component
class AuditTrail {

  private final JdbcClient jdbc;

  AuditTrail(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @EventListener
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void on(IdentityDeactivated event) {
    jdbc.sql("INSERT INTO audit (kind, identity_id, at) VALUES ('DEACTIVATED', :id, :at)")
        .param("id", event.identityId())
        .param("at", Timestamp.from(event.occurredAt()))
        .update();
  }
}
```

The method is `public` so that Spring's transactional proxy applies to it.

## Personal data and untrusted text

- `SignInFailed`, `SignInEmailRequested` and email `CredentialAdded` and `CredentialRevoked`
  carry an email address. That is deliberate: "40 failed codes for one address" is the most
  useful signal of an attack. Treat it as personal data when you store it.
- Addresses are normalized (trimmed, lower-cased). An address is in an event only when it is safe
  to carry (bounded length, no control characters, `EmailAddresses.isSafe`). Otherwise the field
  is null, and a failed sign-in's reason is `MALFORMED`.
- A passkey's label, an API key's name and a JWT issuer label are text that callers supply, and
  are not validated. A role name or permission may also come from your own admin screens. Treat
  these as untrusted when you log them: escape them, or log them as structured fields.
- No event ever carries a link token, a code, an API key, an enrollment token value or a hash.

## Reading the actor and the client

Events do not carry the acting identity or the client's address. Listeners run on the request
thread, so read them there:

```java
@EventListener
void on(RoleAssigned event) {
  Authentication actor = SecurityContextHolder.getContext().getAuthentication();
  String client = null;
  if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
    client = attributes.getRequest().getRemoteAddr();
  }
  log.info("role {} assigned to {} by {} from {}",
      event.roleName(), event.identityId(), actor == null ? "nobody" : actor.getName(), client);
}
```

`getRemoteAddr()` is the proxy's address unless your application is configured for forwarded
headers; who never reads `X-Forwarded-For` itself.

`SignInEmailRequested` is the exception: it is published on the email thread, where the request
is gone. With a `ContextPropagatingTaskDecorator`, the trace context is still there
([Tracing the send](email-links.md#tracing-the-send)).

## Slow listeners

A listener that does slow work slows the request it runs on, and work that differs by failure
reason could make some failures take longer than others. Keep listeners quick, and move slow work
(a network call, a large write) off the request thread:

```java
@Async
@EventListener
void on(SignInFailed event) {
  // ship to a log service
}
```

Enable `@Async` with `@EnableAsync`. An asynchronous listener no longer has the actor or the
client; read them first or leave that to a synchronous listener.

## Spring's own sign-in events

Spring Security publishes `AuthenticationSuccessEvent` and `AbstractAuthenticationFailureEvent`
for email, passkey and API-key sign-ins, among others. They do not carry who's reason or
credential, and a sign-in can produce more than one. Use who's events for an audit trail and
leave Spring's alone, or you will record each sign-in twice.

## Compatibility

Events are public API. New event types, and new values in the enums above, may appear in a minor
release, so a listener must tolerate values it does not know (a `switch` needs a `default`, and
a catch-all `WhoEvent` listener should ignore what it cannot handle). Removing or renaming an
event, a field or an enum value is a breaking change and comes with an upgrade note.

New record components may also be added to events in a minor release. Read fields through their
accessors, and do not construct who's events or take them apart with record deconstruction
patterns, which break when a component is added.

## Sign-in events and custom handlers

`SignInSucceeded` for email links, email codes and passkeys is published by who's JSON success
handler. If your application defines its own `JsonAuthenticationHandlers` bean with the old
two-argument constructor, it publishes no `SignInSucceeded`. See
[Upgrading](upgrading.md#upgrading-to-0170). API keys and JWTs publish from their authentication
provider and converter, so they are not affected.
