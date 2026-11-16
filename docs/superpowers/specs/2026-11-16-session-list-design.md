# Signed-in sessions: see them, end them

## Why

Someone suspects another person is in their account: a laptop left signed in at a library, a
lost phone, a stolen session cookie. Today who gives them nothing to do about it. With this, they
open a list of where they are signed in, spot the one they do not recognise, and end it. It must
fail on its very next request, whatever session store the application uses.

who is a security library; this closes the last gap between "a session was stolen" and "the
person can do something about it" without a support ticket.

## Goals

- A person sees every browser session of theirs that is still alive: device, how and when it
  signed in, when it was last seen, and which one is the one they are using.
- A person ends any one of them, or every one but their own.
- An ended session is refused on its next request, with any session store: the container's own
  sessions (who's default), Spring Session JDBC, Spring Session Redis.
- An application ends sessions too (support staff, "I lost my laptop" flows) through a service,
  without Spring Session.
- The table stays small without any setup.

## Non-goals

- IP addresses and locations. Not stored, not shown, not an option (owner's decision).
- Recognising the same browser across sessions (grouping, "new device" emails). That needs a
  device cookie and belongs with new-device notifications, a later feature. No fingerprinting.
- JWT and API-key callers. They are not sessions and never appear in the list; API keys already
  have their own list.
- History of past sessions. The list is "where am I signed in now"; `SignInSucceeded`,
  `SignedOut` and `SessionEnded` events are the audit trail.

## Anti-goals

- Exposing a servlet session id anywhere. It is the cookie value: the thing being protected.
- A stale cookie kicking the real person out: ending another session needs a recent sign-in.
- A table that grows forever in an app whose owner never configured anything.
- Making each sign-in method do its own thing: one place records sessions.

## Approaches

**A. Build on Spring Session** (`FindByIndexNameSessionRepository`): list with
`findByPrincipalName`, end with `deleteById`, device in a session attribute. No new table, but
nothing for apps on container sessions (who's default), and Spring Session JDBC publishes no
events when it deletes expired rows.

**B. who keeps its own record of sessions (chosen).** A `who_session` table, one row per browser
sign-in. The browser session carries the row's id; who's per-request check refuses an ended row.
Works with any store; costs a table, a migration and a little database work per request.

The owner chose B: a security feature that depends on an optional dependency is missing in most
apps, and B extends what who already does (re-check every session on every request).

## Design

### The record

`who_session`, one row per browser sign-in:

| Column | |
|---|---|
| `id` | random UUID; the only id ever shown or accepted |
| `identity_id` | |
| `credential_id` | the credential that signed in |
| `sign_in_method` | `SignInMethod` name |
| `signed_in_at` | |
| `last_seen_at` | |
| `user_agent` | the request's `User-Agent` at sign-in, capped (512 characters), nullable |
| `ended_at` | nullable; set when ended |

Indexed by `identity_id`, and by whatever the purge query needs (`ended_at`, `last_seen_at`).
Added to `who-jdbc-schema.sql` and `who-jdbc-changelog.xml` like who's other tables.

### Recording: one place, every sign-in method

Every browser sign-in (email link and code, passkey, provider, pending sign-up) ends in
`WhoSessionSecurityContextRepository.saveContext` with a `WhoAuthenticationToken` that carries
identity, credential id, sign-in method and time. On a new sign-in there it inserts the row and
stores the row id in `WhoSession` (a new component, `sessionId`). No sign-in method changes; a
future one gets this for free.

Loading the session on each request (same class) also:

- refuses a row that is ended, or missing, or past the backstop below: the request is signed out
  (who's usual `401`), the servlet session's who attributes are removed;
- bumps `last_seen_at`, written at most once per interval (as API keys do with
  `who.api-key.last-used-interval`), so a busy session costs one write every few minutes, not one
  per request.

A `WhoSession` without a `sessionId` (saved before this release) is refused like an ended one:
those people sign in once more. Documented in the upgrade notes.

### Ending

- who's logout ends the current row (alongside its existing `SignedOut` event).
- An `HttpSessionListener` (`sessionDestroyed`, for invalidation and container timeout) ends the
  row, so the common case is tidy promptly.
- **Backstop, always right:** a row whose `last_seen_at` is older than the session timeout counts
  as ended, in the list and in the per-request check. This covers crashes, restarts and Spring
  Session JDBC, which tells nobody when it expires a session. The timeout is the one Spring Boot
  uses (`server.servlet.session.timeout`, or `spring.session.timeout` when set).
- Ending through `WhoSessions` (below) publishes `SessionEnded` with a new
  `SessionEndReason.REVOKED`: the session was ended deliberately, by its owner from another
  session or by the application.

### Purging dead rows

- `int purgeEndedSessions()` on `WhoSessions` deletes at most one batch of dead rows (ended, or
  past the backstop) and returns how many it deleted. Batch size is configurable, default 100. It
  never tries to empty the table; a caller wanting more calls again until it gets less than a
  batch back. No long locks, no huge transactions.
- Publishes an event with the count when it deleted anything (like `OrphanedCredentialsPurged`).
- **Auto-purge, on by default:** each sign-in calls it once. `who.sessions.auto-purge=false` turns
  that off; the application can then call the method on its own schedule. who starts no threads.
- Deletion only happens through this method, so there is one rule wherever it is triggered.

### Endpoints

Mounted with who's other endpoints, on the browser (session) chain only:

- `GET /who/sessions`: the caller's live sessions, newest first. Each: `id`, `device` (a coarse
  label from the User-Agent, e.g. "Firefox on macOS"), `userAgent` (raw, for apps that want their
  own wording), `signInMethod`, `signedInAt`, `lastSeenAt`, `current` (true for the caller's own).
- `DELETE /who/sessions/{id}`: ends one of the caller's sessions. Another identity's id, an
  unknown id, or an already-dead one is `404`. Ending a session other than the current one needs a
  recent sign-in (`ReauthenticationRequiredException`, `401`). Ending the current one is signing
  out and needs none.
- `DELETE /who/sessions?others=true`: ends every one of the caller's sessions but the current
  one; needs a recent sign-in. Answers with how many it ended.
- CSRF applies as on who's other session endpoints. An API key or JWT caller gets `403`.

The device label is a small, conservative parser (browser family and OS family, "Unknown browser"
otherwise); no user-agent library dependency.

### For applications

`WhoSessions` service (bean, auto-configured): `list(identityId)`, `end(sessionId)`,
`endAll(identityId)`, `purgeEndedSessions()`. It trusts its caller, as `ApiKeyService` does; the
endpoints decide who may end what. The sessions guide's Spring Session "sign out everywhere"
recipe is replaced by `endAll`.

`endAll` is also the natural call when an identity is deactivated or a credential revoked; who
already refuses those sessions on their next request, so ending their rows there is tidiness, and
the builder decides whether to do it.

### Configuration

| Property | Default | |
|---|---|---|
| `who.sessions.auto-purge` | `true` | Purge one batch of dead rows on each sign-in |
| `who.sessions.purge-batch-size` | `100` | Rows per purge call |
| `who.sessions.last-seen-interval` | `5m` | How often `last_seen_at` is written for a busy session |

### Observability

`SessionEnded` with reason `REVOKED` lands on the existing `who.sessions.ended` meter with its
reason tag; the purge event gets a counter. The observability example's dashboard picks both up.

## Upgrading (0.19.0)

- New table `who_session` (bundled schema and Liquibase changelog; Flyway users add it).
- Every existing session signs in once more after the upgrade.
- `WhoSession` gains `sessionId`.
- The sessions guide's Spring Session recipe is replaced by `WhoSessions.endAll`.

## Left to the builder

- Module placement, following who's pattern: domain and SPI in `who-core`, JDBC repository in
  `who-jdbc`, endpoints and the recording in `who-spring-security`, wiring in `who-autoconfigure`.
- Endpoint implementation style (filter like `EmailAddressFilter`, or otherwise), JSON shapes
  beyond the fields above, the purge event's name.
- Whether `endAll` runs on identity deactivation and credential revocation.
- The exact device-label rules.
- An example: the web-and-api example's account page gains the list; the SPA example may too.
