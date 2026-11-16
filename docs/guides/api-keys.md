# API Keys

Who supports API key authentication via `who-apikey`. Every key expires, holds only the
permissions it was made for, and records when it was last used. Keys are hashed before storage:
the raw key is shown once, when it is made, and cannot be retrieved again.

## Turn it on

Add the dependency (its version comes from who's BOM). API keys need no settings:

```xml
<dependency>
    <groupId>org.jwcarman.who</groupId>
    <artifactId>who-apikey</artifactId>
</dependency>
```

`who.api-key.enabled=false` turns them off without removing the jar.

## Wiring the filter

With no `SecurityFilterChain` of your own there is nothing to wire: who's default chain runs the
filter (see [Getting Started](getting-started.md#secure-the-app)). A request the key signs in
needs no CSRF token and never gets a session; a request that already has a session ignores the
key and keeps its CSRF check. In a chain of your own, `WhoSecurity.all()`
mounts API keys with every other sign-in method.

The filter is not registered globally. It only runs in chains that add it. To add only API keys to a chain, use `WhoApiKeySecurity`:

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
| A malformed, unknown, revoked or expired key, or a key whose identity is not active | `401` at once, even on endpoints that permit anonymous access. |

A request that is already authenticated (by a session, for example) ignores the header.

The `401` is the same answer every who sign-in gives: the filter hands a `SignInFailedException` to your `@RestControllerAdvice` through who's exception handoff, and falls back to a bare `401` when nothing handles it. It never says why the key was refused.

> **Breaking change.** Before, a request with a bad key went on as anonymous, so an endpoint that permits anonymous access answered it. Now it gets `401`. A client that sends a stale key to a public endpoint must drop the header or use a valid key.

## Events

A request whose key is refused publishes a who sign-in event; a valid key publishes none, since
it authenticates every request (Spring's `AuthenticationSuccessEvent` still reports each one):

- `SignInFailed` with method `API_KEY` and reason `UNKNOWN_KEY` (malformed, unknown, revoked, or linked to no identity), `EXPIRED_KEY`, or `INACTIVE_IDENTITY`, with the identity and credential ids when they are known.

`ApiKeyService.create` and `rotate` publish `CredentialAdded` for the new key, and `revoke` publishes `CredentialRevoked`, all with kind `API_KEY` and the key's name as the label.

No event carries the key or its hash, and who never logs the key.

## Making a key

A key needs a name, a lifetime and its scopes. There is no default for either: the caller
decides.

```java
NewApiKey created = apiKeyService.create(
    identity, "Nightly export", Duration.ofDays(90), Set.of("report.read"));
String rawKey = created.key(); // show it once: it cannot be retrieved again
```

`create` refuses, with an `IllegalArgumentException` that says which, a blank name, a lifetime
that is not positive or is longer than `who.api-key.max-lifetime` (default 365 days), an empty
scope set, a name or scope longer than 255 characters, or a scope the owner does not hold now. `NewApiKey` holds the key's id, name, raw key,
scopes and expiry; its `toString()` leaves the raw key out.

### Scopes

A key's scopes are permissions its owner held when it was made. At each sign-in, the key gets its
scopes intersected with its owner's permissions **at that moment**. Removing a role from the
owner takes the permission away from every one of their keys at once, and nothing needs cleaning
up. An owner who has lost every scoped permission still signs in with the key, with no
permissions, so `@RequiresPermission` refuses every guarded call.

### Expiry

Every key expires. An expired key is refused with `401` and `SignInFailed` reason `EXPIRED_KEY`.
It stays in the database, and in `findByIdentity`, until it is revoked.

## Listing keys

```java
List<ApiKey> keys = apiKeyService.findByIdentity(identity.id());
```

An identity's keys, by name: each `ApiKey` has the id, name, scopes, creation time, expiry, last
use (`null` when never used) and whether it has expired. No secret is in it. A key unused for a
long time is a good one to revoke.

Its last use is recorded by the sign-in itself, at most once per `who.api-key.last-used-interval`
(default 5 minutes) per key, so a busy key costs one database write per interval, not one per
request; a key used within the interval costs no statement at all. A failure to record it is
logged at WARN and never refuses the key.

## Rotating a key

```java
NewApiKey replacement = apiKeyService.rotate(keyId, Duration.ofDays(1));
```

`rotate` makes a new key with the old one's name and scopes, and the old one's lifetime counted
from now (capped at `who.api-key.max-lifetime`). The old key keeps working for the overlap, so a
client can switch without a hard cutover, and then expires; it expires sooner if it already
would. The overlap is at most 7 days, and zero cuts over at once. `rotate` refuses a key that is
gone or already expired.

## Exposing key management over HTTP

`ApiKeyService` trusts its caller: your code decides who may manage which key.

- `rotate` and `revoke` take only a key id. Before you call them with an id from a request,
  check that it is one of the caller's keys, for example that it is in
  `findByIdentity(caller.identity().id())`, and answer `404` otherwise. Without that check, one
  person can rotate or revoke another's key.
- `create` checks the scopes against the owner's permissions, not against how the request
  signed in. A request signed in with a key scoped to `report.read` could otherwise make a new key
  with everything its owner holds. Either refuse key management to requests signed in with a key
  (`WhoAuthenticationToken.signInMethod()` is `API_KEY`), so a leaked key cannot make itself
  permanent, or allow only scopes the request's own principal has.
- Listing an identity's keys shows what each is for, what it may do and when it was last used: a
  map for whoever holds a leaked key. Serve the list only to a person, as GitHub and Stripe do. A
  machine that needs to see its own expiry can read the key it signed in with:
  `WhoAuthenticationToken.credentialId()` is its id, taken from the sign-in, never from the
  request.

## The key's format

A key is the prefix, 30 random base62 characters (about 178 bits), and a 6-character base62
CRC-32 checksum of the rest, the shape GitHub uses for its tokens:

```
who_Lc4zRJstao56kTIzyhn3IH0I0c4ZQp372txm
```

The prefix makes a leaked key recognizable to a secret scanner. With the default prefix, this
regular expression finds who keys:

```
\bwho_[0-9A-Za-z]{36}\b
```

The checksum lets a scanner, and who, tell a real key from look-alike text: who refuses a key
whose checksum is wrong as `UNKNOWN_KEY` without querying the database. It is not a security
check.

Set your own prefix with `who.api-key.prefix`: letters, digits and `_`, 2 to 20 characters,
ending in `_`, or startup fails. who looks keys up by their hash, so changing the prefix leaves
existing keys working.

## Configuring the header name

The default header is `X-API-Key`. Override it with:

```yaml
who:
  api-key:
    header-name: X-API-Key  # default
```

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
