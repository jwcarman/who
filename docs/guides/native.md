# Native Images

Who works in a GraalVM native image with no native configuration in your app. Its jars carry what the image needs, so you add who to a Spring Boot app, build it with Spring Boot's native support (`process-aot` and GraalVM's `native-maven-plugin`), and the binary signs users in with email links and passkeys as the JVM build does.

Who's native support is proven against GraalVM 25 and Spring Boot 4.1 in who's own build. `who-native-tests` holds one small app per sign-in method (email link and code, passkey, API key and JWT), each with only the who modules that method needs and no reflection, resource or proxy configuration of its own. Each is compiled native and driven over HTTP against PostgreSQL: sign in, `/who/me`, a permission check, a refused credential, and the security events they publish. A separate Spring Boot app built on who also runs its whole browser suite (email links, P-256 and Ed25519 passkeys, roles, invitations and passkey revocation) against its native binary.

## What who ships

Every module that has database files ships GraalVM reachability metadata at `META-INF/native-image/org.jwcarman.who/<module>/reachability-metadata.json`. It registers that module's Liquibase changelogs (`org/jwcarman/who/<module>/db/*.xml`) and its schema script (`who-<module>-schema.sql`). `who-autoconfigure` registers the master changelog, `org/jwcarman/who/db/who-changelog.xml`. A module on the classpath therefore always brings its own files, and none of them is dropped silently from the image.

`who-passkey` also registers reflection hints with Spring AOT (`PasskeyRuntimeHints`, listed in `META-INF/spring/aot.factories`). Spring Security's WebAuthn JSON and webauthn4j's CBOR model are read reflectively, and neither library ships native hints for them yet. Which key type and attestation format arrive depends on the user's authenticator, so who registers whole families of types, not only the ones a test happens to use:

- every class in `org.springframework.security.web.webauthn.api` and `org.springframework.security.web.webauthn.jackson`;
- every class in webauthn4j's `com.webauthn4j.data.attestation`, `.attestation.authenticator`, `.attestation.statement` and `com.webauthn4j.data.client` (all COSE key types, all attestation statements, client data).

Sessions are covered too, including Spring Session JDBC, which stores session attributes with Java serialization. A native image needs a serialization hint for each class that is serialized, and who ships them. `who-spring-security` registers `WhoSession`, `UUID`, `Instant` and `java.time.Ser`. `who-passkey` registers every `Serializable` class in `org.springframework.security.web.webauthn.api`, because the session holds the creation or request options during a passkey ceremony, plus `Duration`, `Boolean`, `HashSet`, `Arrays$ArrayList` and `byte[]`. Your app needs no hints for sessions. See [Sessions](sessions.md).

Who's own types need nothing: Spring AOT covers its configuration, beans, properties and `@Transactional` proxies.

Two code choices keep who native-friendly:

- Who's JDBC repositories are not annotated `@Repository`. Who's `@Bean` methods return them as interfaces, and Spring AOT cannot generate the class proxy that `@Repository` exception translation needs for a bean it only knows by its interface. Exceptions are still translated: `JdbcClient` throws Spring's `DataAccessException` hierarchy.
- With `who.initialize-schema: never`, who does not look up its schema scripts at all.

## Set who's switches at build time

Spring AOT decides at build time which beans the image contains, so it evaluates `@ConditionalOnProperty` then, not when the binary starts. who switches email links on with `who.email-link.link-base-url` and passkeys with `who.passkey.rp-id`. If a property is missing when the image is built, the feature is missing from the binary, even if you pass the property when it starts, and startup fails with a missing bean (such as `EmailCredentialService`).

Put both in `application.yml`, with the value you use most or a placeholder with a default, and override it at run time as usual:

```yaml
who:
  email-link:
    link-base-url: ${LINK_BASE_URL:http://localhost:8080/signin}
  passkey:
    rp-id: ${RP_ID:localhost}
```

The same applies to Spring Boot's own switches, such as `spring.security.oauth2.resourceserver.jwt.public-key-location` or `jwk-set-uri` for a JWT resource server.

## Liquibase

Run who's changelogs through Spring Boot's Liquibase integration (`spring-boot-starter-liquibase`), as the [Database Setup](database.md) guide describes. Liquibase itself is covered by its entry in GraalVM's reachability metadata repository, which `native-maven-plugin` adds when `add-reachability-metadata` runs. Spring Boot registers only `db/changelog/**` for your own changelogs; keep your app's changelogs there, or register their location yourself.

## Your own `@Repository` beans

The proxy limit that who avoids applies to your code too. A `@Repository` bean that a `@Bean` method returns as an interface type fails natively at startup:

```text
UnsupportedOperationException: CGLIB runtime enhancement not supported on native image.
Make sure to enable Spring AOT processing to pre-generate '...$$SpringCGLIB$$0' at build time.
```

Either return the concrete class from the `@Bean` method, so AOT knows what to proxy, or drop `@Repository` when the class uses `JdbcClient` or `JdbcTemplate`, which already translate exceptions. Component-scanned `@Repository` classes are fine, because AOT sees the class.

Do not work around it with `spring.persistence.exceptiontranslation.enabled=false`. It turns translation off for every repository, and AOT reads it at build time.
