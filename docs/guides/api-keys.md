# API Keys

Who supports API key authentication via `who-apikey`. API keys are hashed before storage — the raw key is shown once at creation and cannot be retrieved again.

## Wiring the filter

The filter is not registered globally. It only runs in chains that add it. Add it with `WhoApiKeySecurity`:

```java
http.with(WhoApiKeySecurity.apiKey(), Customizer.withDefaults());
```

`WhoApiKeySecurity.apiKey()` takes the `ApiKeyAuthenticationFilter` bean that `who-autoconfigure` provides. To use your own instance, call `WhoApiKeySecurity.apiKey(filter)`. The filter goes before `AuthorizationFilter` and skips requests that are already authenticated.

The filter is Spring Security's `AuthenticationFilter`, and keys sign in through an authentication manager built the way Spring builds its own: it publishes Spring's authentication success and failure events through your `AuthenticationEventPublisher` bean, if you have one, and each sign-in is observed when your application observes (for example, with Actuator).

What a request gets depends on its header:

| Header | Result |
|---|---|
| Absent or blank | The request goes on unauthenticated, as before. |
| A valid key | The request is authenticated as the key's identity and goes on. The sign-in lasts for this request only: no session is created. |
| An unknown or revoked key, or a key whose identity is not active | `401` at once, even on endpoints that permit anonymous access. |

A request that is already authenticated (by a session, for example) ignores the header.

The `401` is the same answer every who sign-in gives: the filter hands a `SignInFailedException` to your `@RestControllerAdvice` through who's exception handoff, and falls back to a bare `401` when nothing handles it. It never says why the key was refused.

> **Breaking change.** Before, a request with a bad key went on as anonymous, so an endpoint that permits anonymous access answered it. Now it gets `401`. A client that sends a stale key to a public endpoint must drop the header or use a valid key.

## Events

A request whose key is refused publishes a who sign-in event; a valid key publishes none, since
it authenticates every request (Spring's `AuthenticationSuccessEvent` still reports each one):

- `SignInFailed` with method `API_KEY` and reason `UNKNOWN_KEY` (unknown, revoked, or linked to no identity) or `INACTIVE_IDENTITY`, with the identity and credential ids when they are known.

`ApiKeyService.create` publishes `CredentialAdded` and `revoke` publishes `CredentialRevoked`, both with kind `API_KEY` and the key's name as the label.

No event carries the key or its hash, and who never logs the key.

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
