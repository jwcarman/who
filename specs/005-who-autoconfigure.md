# who-autoconfigure: Spring Boot autoconfiguration

## What to build

Create the `who-autoconfigure` Maven module that wires all Who modules together as
Spring beans. Also create `who-spring-boot-starter` as a zero-code convenience POM
that pulls in the most common combination of modules.

### Module setup: who-autoconfigure

New Maven module `who-autoconfigure` with dependencies:
- `who-core`
- `who-jdbc` (optional — `<optional>true</optional>`)
- `who-rbac` (optional)
- `who-jwt` (optional)
- `spring-boot-autoconfigure`
- `spring-boot-configuration-processor` (optional — annotation processor for IDE metadata)
- `spring-boot-starter-test` (test scope)

The `spring-boot-configuration-processor` must be declared as an annotation processor
so it generates `META-INF/spring-configuration-metadata.json` at compile time from the
`@ConfigurationProperties` class. This gives consumers IDE autocompletion and documentation
for all `who.*` properties.

Mark `who-jdbc`, `who-rbac`, and `who-jwt` as `<optional>true</optional>` in the POM
so consuming applications control which modules are on the classpath.

### WhoProperties

`@ConfigurationProperties(prefix = "who")` class:

```
who.jdbc.schema-locations   — List<String>, default: ["classpath:org/jwcarman/who/jdbc/schema.sql"]
who.rbac.schema-locations   — List<String>, default: ["classpath:org/jwcarman/who/rbac/schema.sql"]
who.jwt.schema-locations    — List<String>, default: ["classpath:org/jwcarman/who/jwt/schema.sql"]
```

(Schema initialization is delegated to Spring Boot's `spring.sql.init.*` — these properties
are informational hints for documentation purposes, or can be used with `ResourceDatabasePopulator`
if the application wants auto-init.)

### WhoAutoConfiguration

`@AutoConfiguration` class. All beans use `@ConditionalOnMissingBean` so the application
can override any of them.

**Always registered:**
```java
@Bean
@ConditionalOnMissingBean
WhoService whoService(CredentialIdentityRepository credentialIdentityRepository,
                      IdentityRepository identityRepository,
                      List<PermissionsResolver> permissionsResolvers)
```

**Conditional on who-jdbc being present:**
```java
@Bean @ConditionalOnMissingBean @ConditionalOnClass(JdbcIdentityRepository.class)
IdentityRepository identityRepository(JdbcClient jdbcClient)

@Bean @ConditionalOnMissingBean @ConditionalOnClass(JdbcCredentialIdentityRepository.class)
CredentialIdentityRepository credentialIdentityRepository(JdbcClient jdbcClient)
```

**Conditional on who-rbac being present:**
```java
@Bean @ConditionalOnMissingBean @ConditionalOnClass(RbacPermissionsResolver.class)
RbacPermissionsResolver rbacPermissionsResolver(IdentityRoleRepository identityRoleRepository,
                                                 RolePermissionRepository rolePermissionRepository)

@Bean @ConditionalOnMissingBean @ConditionalOnClass(RbacService.class)
RbacService rbacService(RoleRepository roleRepository,
                         RolePermissionRepository rolePermissionRepository,
                         IdentityRoleRepository identityRoleRepository)

// ... plus JDBC repo beans for who-rbac
```

**Conditional on who-jwt being present:**
```java
@Bean @ConditionalOnMissingBean @ConditionalOnClass(WhoJwtAuthenticationConverter.class)
WhoJwtAuthenticationConverter whoJwtAuthenticationConverter(
        JwtCredentialRepository jwtCredentialRepository,
        WhoService whoService)
```

Register the autoconfiguration class in:
`src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

### Module setup: who-spring-boot-starter

New Maven module `who-spring-boot-starter` — a POM-only module (no Java source) with dependencies:
- `who-autoconfigure`
- `who-core`
- `who-jdbc`
- `who-rbac`
- `who-jwt`

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
- `List<PermissionsResolver>` injected into `WhoService` will be empty if `who-rbac` is absent — `WhoService` handles this gracefully (empty permissions set)
- Do not use `@ComponentScan` — register beans explicitly via `@Bean` methods to avoid scanning surprises
- `who-spring-boot-starter` has no `src/` directory — it is purely a dependency aggregator POM
- The `@EnableMethodSecurity` annotation should NOT be in autoconfiguration — leave that to the application to opt into
