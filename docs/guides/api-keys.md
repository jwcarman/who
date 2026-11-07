# API Keys

Who supports API key authentication via `who-apikey`. API keys are hashed before storage — the raw key is shown once at creation and cannot be retrieved again.

## Wiring the filter

The filter is not registered globally. It only runs in chains that add it. Add it with `WhoApiKeySecurity`:

```java
http.with(WhoApiKeySecurity.apiKey(), Customizer.withDefaults());
```

`WhoApiKeySecurity.apiKey()` takes the `ApiKeyAuthenticationFilter` bean that `who-autoconfigure` provides. To use your own instance, call `WhoApiKeySecurity.apiKey(filter)`. The filter goes before `AuthorizationFilter` and skips requests that are already authenticated.

## Configuring the header name

The default header is `X-API-Key`. Override it with:

```yaml
who:
  api-key:
    header-name: X-API-Key  # default
```

## Generating a key

```java
Identity identity = whoService.createIdentity();
String rawKey = apiKeyService.create(identity, "Production server");
// Show rawKey to the user once — it cannot be retrieved again
```

The returned key is `who_` followed by 43 URL-safe characters (256 random bits). Store it securely — the library stores only the hash.

## Using the key

```bash
curl https://api.example.com/endpoint \
  -H "X-API-Key: who_<your-key>"
```

## Revoking a key

Call `ApiKeyService.revoke` with the credential id:

```java
apiKeyService.revoke(credentialId);
```

This deletes the key and its identity link.

Revocation takes effect immediately — the next request using that key will be denied.
