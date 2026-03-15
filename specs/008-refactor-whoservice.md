# Refactor WhoService: single PermissionsResolver + Optional chaining

## What to build

Two focused cleanups to `WhoService` in `who-core`:

1. Replace `List<PermissionsResolver>` with a single `PermissionsResolver` — supporting
   multiple resolvers is speculative complexity. Anyone who needs to combine sources can
   implement a composite resolver themselves.

2. Replace the imperative null-check chain in `resolve()` with proper Optional chaining.

### WhoService changes

**Constructor:** replace `List<PermissionsResolver> permissionsResolvers` with
`PermissionsResolver permissionsResolver`.

**`resolve()` method** — replace current implementation with:

```java
public Optional<WhoPrincipal> resolve(Credential credential) {
    return credentialIdentityRepository.findIdentityIdByCredentialId(credential.id())
        .flatMap(identityRepository::findById)
        .filter(identity -> identity.status() == IdentityStatus.ACTIVE)
        .map(identity -> new WhoPrincipal(
            identity.id(),
            permissionsResolver.resolve(identity)
        ));
}
```

### WhoAutoConfiguration changes

Update the `whoService` bean method to inject a single `PermissionsResolver` instead
of `List<PermissionsResolver>`:

```java
@Bean
@ConditionalOnMissingBean
WhoService whoService(CredentialIdentityRepository credentialIdentityRepository,
                      IdentityRepository identityRepository,
                      PermissionsResolver permissionsResolver)
```

## Acceptance criteria

- [ ] `WhoService` constructor takes a single `PermissionsResolver`, not a `List`
- [ ] `WhoService.resolve()` uses Optional chaining — no `isEmpty()`, no `.get()`, no mutable collections
- [ ] `WhoAutoConfiguration` injects a single `PermissionsResolver` bean
- [ ] All existing `WhoService` tests updated to reflect the single-resolver contract
- [ ] `mvn test` passes (all modules)
- [ ] `mvn -P license verify` passes
- [ ] `progress.txt` updated

## Implementation notes

- `who-rbac` is unaffected — `RbacPermissionsResolver` still implements `PermissionsResolver`
  and is still registered as a `@Bean` in autoconfiguration; it just no longer needs to be
  collected into a list
- No other modules are affected by this change
- Keep the change small — do not refactor anything beyond what is described here
