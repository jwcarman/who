# Native Images

Who works in a GraalVM native image with no native configuration in your app. Its jars carry what the image needs, so you add who to a Spring Boot app, build it with Spring Boot's native support (`process-aot` and GraalVM's `native-maven-plugin`), and the binary signs users in with email links and passkeys as the JVM build does.

Who's native support is proven against GraalVM 25 and Spring Boot 4.1 by a separate Spring Boot app built on who (not by who's own build): it runs its whole browser suite (email links, P-256 and Ed25519 passkeys, roles, invitations and passkey revocation) against the native binary, with no reflection, resource or proxy configuration of its own.

## What who ships

Every module that has database files ships GraalVM reachability metadata at `META-INF/native-image/org.jwcarman.who/<module>/reachability-metadata.json`. It registers that module's Liquibase changelogs (`org/jwcarman/who/<module>/db/*.xml`) and its schema script (`who-<module>-schema.sql`). `who-autoconfigure` registers the master changelog, `org/jwcarman/who/db/who-changelog.xml`. A module on the classpath therefore always brings its own files, and none of them is dropped silently from the image.

`who-passkey` also registers reflection hints with Spring AOT (`PasskeyRuntimeHints`, listed in `META-INF/spring/aot.factories`). Spring Security's WebAuthn JSON and webauthn4j's CBOR model are read reflectively, and neither library ships native hints for them yet. Which key type and attestation format arrive depends on the user's authenticator, so who registers whole families of types, not only the ones a test happens to use:

- every class in `org.springframework.security.web.webauthn.api` and `org.springframework.security.web.webauthn.jackson`;
- every class in webauthn4j's `com.webauthn4j.data.attestation`, `.attestation.authenticator`, `.attestation.statement` and `com.webauthn4j.data.client` (all COSE key types, all attestation statements, client data).

Who's own types need nothing: Spring AOT covers its configuration, beans, properties and `@Transactional` proxies.

Two code choices keep who native-friendly:

- Who's JDBC repositories are not annotated `@Repository`. Who's `@Bean` methods return them as interfaces, and Spring AOT cannot generate the class proxy that `@Repository` exception translation needs for a bean it only knows by its interface. Exceptions are still translated: `JdbcClient` throws Spring's `DataAccessException` hierarchy.
- With `who.initialize-schema: never`, who does not look up its schema scripts at all.

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
