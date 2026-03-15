# who-apikey: add name field to ApiKeyCredential

## What to build

Add a mandatory `name` field to `ApiKeyCredential` so users can identify and
manage their keys (e.g. "Production server", "CI pipeline").

### Changes

**Schema** — add `name` column to `who_api_key_credential`:
```sql
name VARCHAR(255) NOT NULL
```

**`ApiKeyCredential`** — add `name` field:
```java
record ApiKeyCredential(UUID id, String name, String keyHash) implements Credential
```
Compact constructor must null-check `name`.

**`ApiKeyService.create()`** — add `name` parameter:
```java
String create(UUID identityId, String name)
```

**`JdbcApiKeyCredentialRepository`** — update `save()` and mapping to include `name`.

**`WhoAutoConfiguration`** — no changes needed (bean wiring is unaffected).

## Acceptance criteria

- [ ] `who_api_key_credential` schema includes `name VARCHAR(255) NOT NULL`
- [ ] `ApiKeyCredential` record has a `name` field with null-check in compact constructor
- [ ] `ApiKeyService.create()` requires a `name` parameter — no overload without it
- [ ] `name` is persisted and retrievable via the repository
- [ ] `mvn test` passes (all modules)
- [ ] `mvn -P license verify` passes
- [ ] `progress.txt` updated

## Implementation notes

- No behavior change — this is purely additive
- Do not provide a default name — callers must supply one explicitly
