Status: implemented
Original: specs/done/005-who-autoconfigure.md (Ralph)
Updated: 2026-11-07 to match the code (who 0.10.0)

# who-autoconfigure: Spring Boot autoconfiguration

## What to build

Create the `who-autoconfigure` Maven module that wires all Who modules together as
Spring beans. Also create `who-spring-boot-starter` as a zero-code convenience POM
that pulls in every module.

### Module setup: who-autoconfigure

New Maven module `who-autoconfigure` with dependencies:
- `who-core`
- `who-spring-security`, `who-jdbc`, `who-rbac`, `who-jwt`, `who-enrollment`, `who-apikey`
  (all `<optional>true</optional>`)
- `spring-boot-autoconfigure`
- `spring-boot-configuration-processor` (optional — annotation processor for IDE metadata)
- `spring-boot-starter-test`, `h2` (test scope)

The `spring-boot-configuration-processor` must be declared as an annotation processor
so it generates `META-INF/spring-configuration-metadata.json` at compile time from the
`@ConfigurationProperties` class. This gives consumers IDE autocompletion and documentation
for all `who.*` properties.

The module dependencies are optional so consuming applications control which modules are on
the classpath.

### WhoProperties

`@ConfigurationProperties(prefix = "who")` class:

```
who.initialize-schema          DatabaseInitializationMode, default: EMBEDDED
who.enrollment.token-expiration Duration, default: 24h
who.api-key.header-name        String, default: "X-API-Key"
who.method-security.enabled    boolean, default: true
```

### Schema initialization

The original design left schema creation entirely to `spring.sql.init.*` and listed
`who.*.schema-locations` hints. Neither property exists. Instead:

`WhoDataSourceScriptDatabaseInitializer` (a `DataSourceScriptDatabaseInitializer`) runs the
`schema.sql` of each Who module found on the classpath, in dependency order
(jdbc, rbac, jwt, enrollment, apikey). It detects a module by a sentinel class (for example
`org.jwcarman.who.jdbc.JdbcIdentityRepository`). The mode comes from `who.initialize-schema`:
`EMBEDDED` (default) runs only against an embedded database, `ALWAYS` always, `NEVER` never.
The bean is `@ConditionalOnMissingBean` and `@ConditionalOnSingleCandidate(DataSource.class)`.

### WhoAutoConfiguration

`@AutoConfiguration` class with `@EnableConfigurationProperties(WhoProperties.class)`. All
beans use `@ConditionalOnMissingBean` so the application can override any of them (except
the API-key `FilterRegistrationBean`, below).

**Always registered:**
```java
@Bean @ConditionalOnMissingBean
WhoDataSourceScriptDatabaseInitializer whoDataSourceScriptDatabaseInitializer(DataSource, WhoProperties)

@Bean @ConditionalOnMissingBean
PermissionsResolver permissionsResolver()   // returns an empty set for every identity

@Bean @ConditionalOnMissingBean
WhoService whoService(CredentialIdentityRepository credentialIdentityRepository,
                      IdentityRepository identityRepository,
                      PermissionsResolver permissionsResolver)
```

Module beans live in static nested `@Configuration(proxyBeanMethods = false)` classes, each
guarded by `@ConditionalOnClass(name = "...")`, so a missing module never causes a class
loading error. Repositories are built from the `JdbcClient` bean.

**`JdbcRepositoriesConfiguration`** — on `org.jwcarman.who.jdbc.JdbcIdentityRepository`:
`IdentityRepository`, `CredentialIdentityRepository`.

**`RbacConfiguration`** — on `org.jwcarman.who.rbac.RbacPermissionsResolver`:
`RoleRepository`, `RolePermissionRepository`, `IdentityRoleRepository`,
`RbacPermissionsResolver` (which replaces the empty default resolver), `RbacService`.

**`EnrollmentConfiguration`** — on `org.jwcarman.who.enrollment.WhoEnrollmentService`:
`EnrollmentTokenRepository`, `WhoEnrollmentService` (with `who.enrollment.token-expiration`).

**`JwtConfiguration`** — on `org.jwcarman.who.jwt.WhoJwtAuthenticationConverter`:
`JwtCredentialRepository`, `WhoJwtAuthenticationConverter`.

**`ApiKeyConfiguration`** — on `org.jwcarman.who.apikey.ApiKeyAuthenticationFilter`:
`ApiKeyCredentialRepository`, `ApiKeyService`, `ApiKeyAuthenticationFilter` (with
`who.api-key.header-name`), and a `FilterRegistrationBean` for the filter with
`setEnabled(false)` so the servlet container does not run it outside a security chain.

**`MethodSecurityConfiguration`** — `@EnableMethodSecurity`, on
`org.jwcarman.who.spring.security.RequiresPermission`, when `who.method-security.enabled` is
true or missing, and only if the application has not configured method security itself
(`@ConditionalOnMissingBean` of `PrePostMethodSecurityConfiguration`).

The original design kept `@EnableMethodSecurity` out of autoconfiguration; 0.10.0 turned it on
by default (see `2026-11-07-security-fixes-design.md`, I5). The API-key filter registration is
from the same release (I1).

Register the autoconfiguration class in:
`src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

### Module setup: who-spring-boot-starter

New Maven module `who-spring-boot-starter` — a POM-only module (no Java source) with dependencies:
- `who-autoconfigure`
- `who-core`
- `who-spring-security`
- `who-jdbc`
- `who-rbac`
- `who-jwt`
- `who-enrollment`
- `who-apikey`

This is the "batteries included" starter. Applications that want only a subset depend on
`who-autoconfigure` plus their chosen modules directly.

## Acceptance criteria

- [ ] `who-autoconfigure` module exists and builds successfully
- [ ] `who-spring-boot-starter` module exists as a POM-only module with no Java source
- [ ] Both modules are added to the root `pom.xml` `<modules>` section
- [ ] `WhoAutoConfiguration` is registered in `AutoConfiguration.imports`
- [ ] `WhoService` bean is created by autoconfiguration
- [ ] JDBC repository beans are only created when `who-jdbc` is on the classpath (`@ConditionalOnClass`)
- [ ] `RbacPermissionsResolver` and `RbacService` beans are only created when `who-rbac` is on the classpath
- [ ] `WhoJwtAuthenticationConverter` bean is only created when `who-jwt` is on the classpath
- [ ] All beans use `@ConditionalOnMissingBean` — application can override any of them
- [ ] Schema initialization runs for an embedded database by default and is skipped with `who.initialize-schema=never`
- [ ] The API-key filter is not registered with the servlet container
- [ ] `@RequiresPermission` is enforced without the application declaring `@EnableMethodSecurity`; `who.method-security.enabled=false` leaves it off
- [ ] `spring-boot-configuration-processor` is declared as an optional dependency and generates `META-INF/spring-configuration-metadata.json` at compile time
- [ ] IDE autocompletion works for `who.*` properties (verified by presence of the metadata JSON file in the built jar)
- [ ] Integration test verifies that `WhoService` bean is present when all modules are on classpath
- [ ] Integration test verifies that no bean creation errors occur with only `who-core` + `who-jdbc` on classpath
- [ ] `mvn test` passes
- [ ] `mvn -P license verify` passes
- [ ] Public classes have JavaDoc
- [ ] `progress.txt` updated

## Implementation notes

- Constructor injection only — no `@Autowired`
- `WhoService` takes a single `PermissionsResolver`; without `who-rbac` the default resolver gives every identity an empty permission set
- Do not use `@ComponentScan` — register beans explicitly via `@Bean` methods to avoid scanning surprises
- `who-spring-boot-starter` has no `src/` directory — it is purely a dependency aggregator POM
- An application that wants its own method-security configuration sets `who.method-security.enabled=false` (or declares its own, which the autoconfiguration backs off from)
