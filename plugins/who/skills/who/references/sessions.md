# Sessions

**Contents**

- What who stores
- No saved requests
- Store sessions in the database
- See and end sessions
- Timeouts and cookies
- The last sign-in cookie
- Native images

`WhoWebSecurity.passwordless()` signs users in with a session. This guide covers what who keeps in it, how people see and end their sessions, and how to store sessions in your database with Spring Session JDBC.

Who works with the container's own sessions out of the box. Spring Session is optional, and who has no dependency on it.

## What who stores

Who keeps one attribute in the HTTP session: a `WhoSession` with the identity id, the credential id, how and when they signed in, and the id of who's record of the session (below). It holds ids only, never a password, token or key.

Each sign-in also writes a row to who's `who_session` table: the identity, the credential, the sign-in method and time, when the session was last used, and the browser's `User-Agent`. It holds no IP address and never the servlet session id.

On every request who checks that row, then resolves the identity and credential from the database again. If the row was ended, or the session was idle past its timeout, or the identity is gone or inactive, or the credential is revoked, the request is not authenticated and who invalidates the session. A change takes effect on the next request, not when the session expires.

Who also sets a second attribute, the principal index, which Spring Session uses to find an identity's sessions. It removes both when the user signs out.

During a passkey ceremony the session also holds the ceremony's options until the browser answers.

## No saved requests

`passwordless()` uses a `NullRequestCache`. Spring Security's default request cache saves a request that was denied, so it can redirect there after sign-in. For a single-page app that does no good: the app receives a `401` and navigates by itself. The saved request is also a copy of the whole request, headers and cookies included, which would end up in the session and, with Spring Session JDBC, in your database.

If you want a redirect after sign-in, keep the target in your app, for example in the URL of your sign-in page.

## Store sessions in the database

By default the servlet container keeps sessions in memory. They are lost on a restart and are not shared between instances. Spring Session JDBC stores them in your database instead.

Add the starter:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-session-jdbc</artifactId>
</dependency>
```

Spring Boot configures it from the `DataSource` it already has. Nothing in who changes.

### Create the tables

For a quick start, let Spring Boot create the tables on PostgreSQL with `spring.session.jdbc.initialize-schema: always`. The default, `embedded`, creates them only in an embedded database, so on PostgreSQL it creates nothing.

An application with who already runs Liquibase (see [Database Setup](database.md)), so you can instead set it to `never` and put the tables in your own changelog:

```yaml
spring:
  session:
    jdbc:
      initialize-schema: never
```

Copy the SQL for your database from `org/springframework/session/jdbc/schema-<database>.sql` in the `spring-session-jdbc` jar. For PostgreSQL:

```xml
<changeSet id="spring-session-1" author="app" dbms="postgresql">
  <sql>
CREATE TABLE SPRING_SESSION (
    PRIMARY_ID CHAR(36) NOT NULL,
    SESSION_ID CHAR(36) NOT NULL,
    CREATION_TIME BIGINT NOT NULL,
    LAST_ACCESS_TIME BIGINT NOT NULL,
    MAX_INACTIVE_INTERVAL INT NOT NULL,
    EXPIRY_TIME BIGINT NOT NULL,
    PRINCIPAL_NAME VARCHAR(100),
    CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID CHAR(36) NOT NULL,
    ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
    ATTRIBUTE_BYTES BYTEA NOT NULL,
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID)
        REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE CASCADE
);
  </sql>
</changeSet>
```

The SQL changes between Spring Session versions, so copy it from the version you use. Who's own changelog does not include these tables, because they belong to Spring Session.

The attributes are stored with Java serialization. `WhoSession` and the types it holds are serializable. Your own session attributes must be too.

## See and end sessions

The [TypeScript client](client.md#sessions) has these calls: `who.me.sessions.list`, `who.me.sessions.end` and `who.me.sessions.endOthers`.

A signed-in person can see every browser they are signed in on and sign any of them out, for instance after losing a phone or signing in on a shared computer. who adds the endpoints to every browser chain, beside the caller's [other account endpoints](account.md):

| Request | Answer |
|---------|--------|
| `GET /who/me/sessions` | `200`, the caller's live sessions, newest sign-in first |
| `DELETE /who/me/sessions/{id}` | `204`. The caller's own session signs out at once; any other needs a [recent sign-in](security-model.md#recent-sign-in) |
| `DELETE /who/me/sessions?others=true` | `200` `{"ended": 2}`: every session but the caller's. Needs a recent sign-in |

Each session in the list looks like this:

```json
{
  "id": "0f8e5c1a-6d1b-4e55-9a49-4c9f3a0a7d2e",
  "device": "Firefox on macOS",
  "userAgent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 14.5; rv:132.0) Gecko/20100101 Firefox/132.0",
  "signInMethod": "passkey",
  "signedInAt": "2026-11-16T09:12:44Z",
  "lastSeenAt": "2026-11-16T10:02:10Z",
  "current": true
}
```

The `id` is who's own, not the session cookie, so showing it exposes nothing that could take a session over. `device` is a rough label read from the `User-Agent`; `signInMethod` is `email-link`, `email-code`, `passkey` or `oidc`.

`userAgent`, and `device` read from it, are whatever the browser sent at sign-in, so treat them as untrusted text: render them as text (`textContent`, or your framework's default escaping), never as HTML.

Signing out the current browser through `DELETE /who/me/sessions/{id}` runs the chain's logout handlers, the same as `POST /who/logout`: who's, any you added, CSRF's, clearing the `XSRF-TOKEN` cookie, and Spring Security's `LogoutSuccessEvent`. It answers `204` rather than running the logout success handler.

Answers, as who's [Problem Details](errors.md#whos-answer) like who's other endpoints:

- `401` when signed out, or when ending another session with a sign-in older than `who.security.recent-sign-in`. Sign in again and retry.
- `403` for a caller who is signed in, but not by this browser's session: an API key or a bearer token sent on a chain that also accepts browser sessions, even with a session cookie beside it. A chain that accepts only keys or tokens has no session endpoints: there `/who/me/sessions` is answered like any other path of yours.
- `404` for an id that is not one of the caller's live sessions, whether it is someone else's, unknown or malformed.
- `400` for `DELETE /who/me/sessions` without `others=true`, so a stray request cannot end everything.

Every example app shows the list on its profile or keys page. In JavaScript:

```js
const sessions = await (await fetch('/who/me/sessions')).json();

// X-XSRF-TOKEN as for any other change; see Email Links
await fetch('/who/me/sessions/' + id, { method: 'DELETE', headers: { 'X-XSRF-TOKEN': xsrf() } });
```

### From your own code

`WhoSessions` is a bean, for an admin screen or an incident response:

```java
@Service
class AccountLockdown {

    private final WhoSessions sessions;

    AccountLockdown(WhoSessions sessions) {
        this.sessions = sessions;
    }

    int signOutEverywhere(UUID identityId) {
        return sessions.endAll(identityId);
    }
}
```

`list(identityId)` returns the live sessions, `end(sessionId)` ends one, and `endAll` and `endAllExcept` end several. Each ended session publishes [`SessionEnded`](events.md) with reason `REVOKED`, and its browser is signed out on its next request. Decide who may call these, and expose them yourself.

### When a session ends

A session's row ends when the person signs out, when they sign in again in the same browser, when it is ended as above, and when the servlet container destroys the session. Spring Session JDBC deletes expired sessions without telling the container, so who also treats a row unused for longer than the session timeout as ended: it drops out of the list, and its browser is signed out. A row also ends when who signs a browser out because its identity is inactive or its credential was revoked.

To keep a busy session from writing on every request, who updates `last_seen_at` at most once per `who.sessions.last-seen-interval` (default 5 minutes). So a row's last use can be that much behind, and who counts a row as abandoned only after the session timeout plus that interval. The container still ends its own session at the exact timeout.

### Purging old rows

Ended and abandoned rows are deleted in batches: each sign-in deletes up to `who.sessions.purge-batch-size` (default 100) of them, never the whole table at once. Each batch publishes `EndedSessionsPurged`, counted by the `who.sessions.purged` meter. The purge runs after the sign-in is recorded, and a purge that fails is logged as a warning without failing the sign-in.

To purge on your own schedule instead, turn it off and call `purgeEndedSessions()`, which deletes one batch and returns how many it deleted:

```yaml
who:
  sessions:
    auto-purge: false
```

```java
@Scheduled(fixedDelay = 60_000)
void purgeSessions() {
    while (sessions.purgeEndedSessions() > 0) {
        // keep going until a batch comes back empty
    }
}
```

## Timeouts and cookies

How long a session lasts without a request is not who's setting. With Spring Boot it is `server.servlet.session.timeout` (default 30 minutes). Spring Session JDBC reads it too, unless you set `spring.session.timeout`. who's record of sessions uses the same value: `spring.session.timeout` when Spring Session is present and it is set, else `server.servlet.session.timeout`, read as Spring Boot reads them: a bare number is seconds, and an empty value is unset. who needs sessions to expire, so a timeout of `0` or less ("never") fails startup. Spring Session deletes expired rows on a schedule, by default every minute (`spring.session.jdbc.cleanup-cron`).

Who does not set the cookie flags. Set them yourself:

```yaml
server:
  servlet:
    session:
      timeout: 30m
      cookie:
        same-site: lax
        http-only: true
        secure: true
```

Use `secure: false` only for local development over plain HTTP. See [Email Links](email-links.md#session-cookie).

The recent sign-in window (`who.security.recent-sign-in`) is separate. It limits how long after sign-in a credential may be added (a passkey, a provider account, an email address), another session ended, or an action marked [`@RequiresRecentSignIn`](security-model.md#recent-sign-in) taken, however long the session lasts.

## The last sign-in cookie

After every browser sign-in, who sets the cookie `who_last_sign_in` to the method used: `email-link` (a link or a typed code), `passkey`, or a provider's registration id such as `github`. Your sign-in page can read it from script and mark that button "Last used", so someone who forgot how they signed up does not create a second account another way. It holds nothing secret, is `Secure` and `SameSite=Lax`, lasts 30 days, and signing out leaves it. Turn it off with `who.security.last-sign-in-cookie.enabled=false`.

## Native images

Spring Session JDBC serializes attributes with Java serialization, and a native image needs a hint for each class that is serialized. Who ships them. `who-spring-security` registers `WhoSession`, `UUID`, `Instant` and `java.time.Ser`, `who-passkey` registers the WebAuthn option types and the JDK types a ceremony stores, and `who-oidc` registers the pending sign-up a provider sign-in keeps. Your app needs no hints of its own for sessions. See [Native Images](native.md).
