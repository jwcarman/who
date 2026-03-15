# Refactor WhoJwtAuthenticationConverter: Optional chaining

## What to build

Replace the imperative null-check chain in `WhoJwtAuthenticationConverter.convert()` with
proper Optional chaining:

```java
@Override
public AbstractAuthenticationToken convert(Jwt jwt) {
    return jwtCredentialRepository.findByIssuerAndSubject(jwt.getIssuer().toString(), jwt.getSubject())
        .flatMap(whoService::resolve)
        .map(principal -> new WhoAuthenticationToken(
            principal,
            principal.permissions().stream()
                .map(SimpleGrantedAuthority::new)
                .toList()
        ))
        .orElse(null);
}
```

No other behavior changes — this is a pure refactor.

## Acceptance criteria

- [ ] `WhoJwtAuthenticationConverter.convert()` uses Optional chaining — no `isEmpty()`, no `.get()`
- [ ] All existing `WhoJwtAuthenticationConverter` tests pass without modification
- [ ] `mvn test` passes (all modules)
- [ ] `mvn -P license verify` passes
- [ ] `progress.txt` updated

## Implementation notes

- Only `convert()` changes — constructor, fields, and JavaDoc are untouched
- The `orElse(null)` return is intentional: Spring Security treats a `null` result as unauthenticated
