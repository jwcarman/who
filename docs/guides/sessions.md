# Sessions

`WhoWebSecurity.passwordless()` signs users in with a session. This guide covers what who keeps in it, how to store sessions in your database with Spring Session JDBC, and how to sign an identity out of every device.

Who works with the container's own sessions out of the box. Spring Session is optional, and who has no dependency on it.

## What who stores

Who keeps one attribute in the HTTP session: a `WhoSession` with the identity id, the credential id and the time of sign-in. It holds ids only, never a password, token or key.

On every request who reads the `WhoSession` and resolves the identity and credential from the database again. If the identity is gone or inactive, or the credential is revoked, the request is not authenticated and who invalidates the session. A change in who's tables takes effect on the next request, not when the session expires.

Who also sets a second attribute, the principal index, for "sign out everywhere" below. It removes both when the user signs out.

During a passkey ceremony the session also holds the ceremony's options until the browser answers.

## No saved requests

`passwordless()` uses a `NullRequestCache`. Spring Security's default request cache saves a request that was denied, so it can redirect there after sign-in. For a single-page app that does no good: the app receives a plain `401` and navigates by itself. The saved request is also a copy of the whole request, headers and cookies included, which would end up in the session and, with Spring Session JDBC, in your database.

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

For a quick start, let Spring Boot create the tables: `spring.session.jdbc.initialize-schema: embedded` (the default) does this for an embedded database, and `always` does it for any other.

If you manage the schema with Liquibase, which the [Database Setup](database.md) guide recommends, turn that off and put the tables in your own changelog:

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

## Sign out everywhere

When who saves a `WhoSession`, it also sets the attribute named by Spring Session's `FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME` to the identity id, as a string. Spring Session JDBC copies it into the `PRINCIPAL_NAME` column, and `findByPrincipalName` uses that column. Who uses the attribute's name as a string, so it needs no Spring Session classes.

To end every session of an identity, for example after the user reports a lost device:

```java
@Service
class SessionService {

    private final FindByIndexNameSessionRepository<?> sessions;

    SessionService(FindByIndexNameSessionRepository<?> sessions) {
        this.sessions = sessions;
    }

    void signOutEverywhere(UUID identityId) {
        sessions.findByPrincipalName(identityId.toString())
                .keySet()
                .forEach(sessions::deleteById);
    }
}
```

The keys of the returned map are session ids. Deleting a session signs that browser out on its next request. Who adds no endpoint for this. Decide who may call it, and expose it yourself.

This finds sessions that were created after you upgraded to a version of who that sets the index. Older sessions have no index and expire on their own. See [Upgrading](upgrading.md).

## Timeouts and cookies

How long a session lasts without a request is not who's setting. With Spring Boot it is `server.servlet.session.timeout` (default 30 minutes). Spring Session JDBC reads it too, unless you set `spring.session.timeout`. Spring Session deletes expired rows on a schedule, by default every minute (`spring.session.jdbc.cleanup-cron`).

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

The passkey registration window (`who.passkey.registration-window`) is separate. It limits how long after sign-in a passkey may be added, however long the session lasts.

## Native images

Spring Session JDBC serializes attributes with Java serialization, and a native image needs a hint for each class that is serialized. Who ships them. `who-spring-security` registers `WhoSession`, `UUID`, `Instant` and `java.time.Ser`, and `who-passkey` registers the WebAuthn option types and the JDK types a ceremony stores. Your app needs no hints of its own for sessions. See [Native Images](native.md).
