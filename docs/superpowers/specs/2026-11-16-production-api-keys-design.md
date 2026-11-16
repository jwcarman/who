# API keys that are ready for production

## Intent

Today a who API key lives forever, can do everything its owner can, and leaves no trace of its
use: a leaked key works until someone notices, and no one can tell which keys are stale. This
brings who's keys to what GitLab, GitHub and the cloud providers ask of theirs: every key expires,
holds only the permissions it was made for, says when it was last used, can be listed, and can be
rotated without a hard cutover. It is the roadmap's 0.19 item, pulled forward into the next
release because the API example (`2026-11-16-example-api-design.md`) changes the same service, and
built first so that example shows these keys.

The owner's direction: no backward compatibility is needed (no one uses keys yet); a lifetime is
required; scopes are required; the prefix is configurable; rotation keeps the old key working for
an overlap.

## Goals

- `create(identity, name, lifetime, scopes)` answers `NewApiKey(id, name, key, expiresAt,
  scopes)`; the raw key is in it once and nowhere else.
- Every key expires. The lifetime is required, positive, and at most `who.api-key.max-lifetime`
  (default 365 days).
- Every key has scopes: a non-empty set of permissions, each held by the owner when the key is
  made. A key's effective permissions at each sign-in are its scopes intersected with the owner's
  permissions at that moment.
- Each key records when it was created and last used; last-used is written at most once per
  `who.api-key.last-used-interval` (default 5 minutes) per key.
- `findByIdentity(identityId)` lists an identity's keys, with no secret in them.
- `rotate(keyId, overlap)` makes a replacement with the same name, scopes and lifetime, and the
  old key expires after the overlap (at most 7 days), or earlier if it already would.
- `who.api-key.prefix` (default `who_`) starts every new key, and a checksum ends it, so a secret
  scanner can recognize a who key and tell a real one from look-alike text.
- An expired key is refused with its own `SignInFailed` reason.

## Non-goals

- Notifying anyone before a key expires, or when one is made. The roadmap's security
  notifications cover that, later.
- An admin view of every identity's keys, or bulk revocation.
- Keys tied to an IP range, a referrer or a request rate.
- A migration for keys made before this change: the schema changes in place.

## Anti-goals

- No key that never expires, and no default lifetime: the caller always decides.
- No key broader than its owner, ever: not at creation, not after the owner loses a permission.
- No raw key stored, logged, published, or in any `toString()`.
- No write on every request: last-used is throttled, and a failed write never fails the request.
- Rotation cannot extend a key's life past the overlap.

## Approaches

- **Scopes as the intersection of the key's and the owner's current permissions (chosen by the
  owner):** removing a role takes effect at once and nothing needs cleaning up.
- **Scopes copied at creation:** simpler to read, but a key outlives its owner's demotion.
  Rejected.
- **An empty scope set meaning "all of the owner's permissions":** convenient, but brings back the
  broad default key. Rejected: scopes are required.
- **Rotation as a fresh key plus a manual revoke:** works today, but forces a hard cutover or a
  forgotten old key. Rejected for Stripe's rolled key with an overlap.

## The design

### Key format (my call)

`<prefix><30 random base62 characters><6 base62 characters of CRC-32 of prefix and random>`, the
shape GitHub uses for its tokens. 30 base62 characters carry about 178 bits. who looks a key up by
the SHA-256 of the whole key, as now, so the checksum adds no lookup and changing the prefix leaves
earlier keys working. The checksum is not a security check, only a way for scanners (and who,
before the database lookup) to reject text that is not a key. A key whose checksum is wrong is
refused as `UNKNOWN_KEY` without touching the database.

The prefix: letters, digits and `_`, 2 to 20 characters, ending in `_`. Anything else fails
startup.

### Schema

`who_api_key_credential` gains `created_at`, `expires_at` (both `NOT NULL`) and `last_used_at`
(nullable). A new `who_api_key_scope (credential_id, permission)`, primary key on both, cascading
on the key's deletion. The schema file changes in place; `upgrading.md` says to drop and recreate
the table.

### Service

| Method | Does |
|---|---|
| `NewApiKey create(Identity, String name, Duration lifetime, Set<String> scopes)` | refuses a blank name, a non-positive lifetime or one over the maximum, an empty scope set, or a scope the owner does not hold, with an `IllegalArgumentException` naming which |
| `List<ApiKey> findByIdentity(UUID identityId)` | the identity's keys, by name: id, name, scopes, created, expires, last used; expired keys included and marked |
| `NewApiKey rotate(UUID keyId, Duration overlap)` | refuses an overlap over 7 days, a key that is gone or expired; the new key has the old one's name, scopes and lifetime from now; the old key's expiry becomes the earlier of its own and now plus the overlap |
| `void revoke(UUID keyId)` | as now |

`ApiKey` is a record with no secret in it; `NewApiKey` adds the raw key and leaves it out of
`toString()`. `create` and `rotate` publish `CredentialAdded`, as `create` does now; `rotate`
publishes nothing for the old key until it is revoked.

### Sign-in

After the hash lookup: an expired key fails with a new `SignInFailureReason.EXPIRED_KEY`; a valid
one signs in with a `WhoPrincipal` whose permissions are the key's scopes intersected with the
owner's current permissions (an owner who lost every scoped permission gets a principal with
none, which `@RequiresPermission` refuses). Then, if `last_used_at` is null or older than the
interval, one conditional `UPDATE ... WHERE last_used_at IS NULL OR last_used_at < ?`; an
exception there is logged at WARN by class name and swallowed.

### Settings

| Setting | Default | |
|---|---|---|
| `who.api-key.prefix` | `who_` | checked at startup |
| `who.api-key.max-lifetime` | `365d` | positive, or startup fails |
| `who.api-key.last-used-interval` | `5m` | positive, or startup fails |

The 7-day overlap cap is fixed, not a setting.

### Tests

- Service on Postgres: each refusal in `create` and `rotate`; scopes stored and listed;
  `findByIdentity` leaves out other identities' keys; rotation's expiry rule both ways.
- Sign-in: an expired key fails `EXPIRED_KEY`; a scoped key cannot reach a permission outside its
  scopes; a demoted owner's key loses the permission at once; a wrong checksum fails without a
  query; last-used is written once within an interval and again after it; a failed write still
  signs in.
- Format: generated keys start with the prefix, pass their checksum, and match one regex a scanner
  could use; a bad prefix fails startup.
- The native API-key test passes.

### Docs

`api-keys.md` rewritten around lifetime, scopes, rotation and listing; `configuration.md` gains
the three settings; `events.md` gains `EXPIRED_KEY`; `upgrading.md` (the next release) gives the
new `create` and the table change; CHANGELOG; ROADMAP moves the 0.19 item into the release.

## Left to the builder

The `ApiKey` record's exact name if it clashes, how the owner's current permissions are read (as
who resolves them for a sign-in), the base62 and CRC-32 helpers' home (who-core's `Secrets` is
likely), and test layout.
