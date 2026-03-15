# Simplify WhoAuthenticationToken constructor

## What to build

Simplify `WhoAuthenticationToken` so callers only pass a `WhoPrincipal` — the token
extracts `SimpleGrantedAuthority` instances from the principal's permissions internally.
Eliminates the burden on callers to build the authority list themselves.

### Change

Replace the two-argument constructor with a single-argument one:

```java
public WhoAuthenticationToken(WhoPrincipal principal) {
    super(principal.permissions().stream()
            .map(SimpleGrantedAuthority::new)
            .toList());
    this.principal = requireNonNull(principal, "principal must not be null");
    setAuthenticated(true);
}
```

Update all callers in `who-jwt` and `who-apikey` to drop the second argument.

## Acceptance criteria

- [ ] `WhoAuthenticationToken` has a single constructor taking only `WhoPrincipal`
- [ ] Authorities are derived from `principal.permissions()` internally
- [ ] No caller passes a separate authority list
- [ ] Unit test verifies that a principal with permissions `["a", "b"]` produces a token
      whose `getAuthorities()` returns `[SimpleGrantedAuthority("a"), SimpleGrantedAuthority("b")]`
- [ ] Unit test verifies that a principal with no permissions produces a token with empty authorities
- [ ] `mvn test` passes (all modules)
- [ ] `mvn -P license verify` passes
- [ ] `progress.txt` updated

## Implementation notes

- The unit test belongs in `who-spring-security`
- Update `WhoJwtAuthenticationConverter` and `ApiKeyAuthenticationFilter` to use the
  simplified constructor
