# Your Account

**Contents**

- Who may call what
- Who is signed in
- What a sign-in page can offer
- Sessions
- Passkeys
- Email addresses
- Linked accounts
- API keys
- The last-credential rule

Everything under `/who/me` belongs to the caller: who they are, where they are signed in, their
passkeys, email addresses, linked provider accounts and API keys. Each endpoint checks ownership
itself, so an id that is not the caller's answers `404`, exactly as an unknown id does, and an
application never has to write that check.

Each endpoint lives in the module that owns its data: passkeys in `who-passkey`, addresses in
`who-email-link`, accounts in `who-oidc`, keys in `who-apikey`. Adding the jar turns the endpoints
on, and the module's `enabled=false` turns them off with it.

The [TypeScript client](client.md#your-account) has a call for each one under `who.me`.

## Who may call what

Three kinds of caller:

- **Browser**: a who browser session, with no API key or bearer token on the request. A session
  cookie with an `X-API-Key` or `Authorization: Bearer` header beside it is not a browser, even
  when the header is ignored, so it is answered `403` `BrowserSessionRequiredException`
  (`Who-Error: browser-session-required`) wherever only a browser may act. Send the cookie alone.
- **Recent**: a browser session signed in within `who.security.recent-sign-in` (10 minutes by
  default). An older one gets `401` `ReauthenticationRequiredException`: sign the person in again
  and retry. who checks this before it looks at any id in the path, so a stale session learns
  nothing about what exists.
- **Signed in**: any who sign-in, a browser session, a bearer token or an API key.

With the default `who.base-path`:

| Endpoint | Who may call | Recent sign-in | Success |
|---|---|---|---|
| `GET /who/me` | Signed in | no | `200` |
| `GET /who/sign-in` | Anyone, signed out included | no | `200` |
| `GET /who/me/sessions` | Browser | no | `200` |
| `DELETE /who/me/sessions/{id}` | Browser | only to end another session | `204` |
| `DELETE /who/me/sessions?others=true` | Browser | yes | `200` `{"ended": 2}` |
| `GET /who/me/passkeys` | Browser | no | `200` |
| `POST /who/me/passkeys/options`, `POST /who/me/passkeys` | Browser | yes | `200` |
| `DELETE /who/me/passkeys/{credentialId}` | Browser | yes | `204` |
| `GET /who/me/email-addresses` | Signed in | no | `200` |
| `POST /who/me/email-addresses` | Browser | yes | `202` |
| `POST /who/me/email-addresses/confirmations` | Browser | yes | `200` `{"credentialId": "..."}` |
| `DELETE /who/me/email-addresses/{id}` | Browser | yes | `204` |
| `GET /who/me/accounts` | Signed in | no | `200` |
| `POST /who/me/accounts/{registrationId}` | Browser | yes | a redirect to the provider, or `200` `{"redirectUrl": "..."}` |
| `DELETE /who/me/accounts/{id}` | Browser | yes | `204` |
| `GET /who/me/api-keys` | Browser or bearer token | no | `200` |
| `POST /who/me/api-keys` | Browser or bearer token | a browser only | `201` |
| `POST /who/me/api-keys/{id}/rotations` | Browser or bearer token | a browser only | `201` |
| `DELETE /who/me/api-keys/{id}` | Browser or bearer token | no | `204` |
| `GET /who/me/api-keys/current` | API key | no | `200` |

Every `/who/me` endpoint answers a signed-out request with `401` `NotSignedInException`. A
signed-in caller that is not one the table allows gets `403` `BrowserSessionRequiredException`
(`browser-session-required`), except that a request an API key signed in gets `403`
`ApiKeyCannotManageKeysException` (`api-key-cannot-manage-keys`) at the API-key endpoints, since
a bearer token may manage keys too, and `GET /who/me/api-keys/current` answers `404` to a request
no API key signed in. Like every who failure, these go
through your exception handling (see [Errors](errors.md)), and every POST and DELETE from a browser
needs the CSRF header.

Which chains serve them:

- `GET /who/me` answers on every chain.
- `GET /who/sign-in`, the sessions and the passkey endpoints, and starting a link
  (`POST /who/me/accounts/{registrationId}`), are on chains that sign browsers in. A chain built
  with `WhoSecurity.stateless()` has none of them: there they are answered like any other path of
  yours.
- The email-address, linked-account and API-key endpoints are on both kinds of chain, so a bearer
  token or an API key on a stateless chain can list addresses and accounts, and a bearer token can
  manage keys. What only a browser may do answers `403` (`browser-session-required`) there.

## Who is signed in

`GET /who/me`:

```json
{
  "identityId": "0f8e5c1a-6d1b-4e55-9a49-4c9f3a0a7d2e",
  "permissions": ["task.read", "task.write"],
  "signInMethod": "passkey",
  "recentSignInUntil": "2026-11-17T10:22:44Z",
  "credentials": { "email": 1, "passkey": 2, "account": 0 }
}
```

- `permissions` are the identity's current permissions; for an API key, those its scopes keep.
- `signInMethod` is how this request signed in, spelled as the [sessions list](sessions.md#see-and-end-sessions)
  spells it: `email-link`, `email-code`, `passkey`, `oidc`, `api-key` or `jwt`. For a browser it
  is how its session signed in, and `null` for a session saved before who recorded that.
- `recentSignInUntil` is when the browser session's sign-in stops being recent. It may already have
  passed: compare it to the current time to decide whether to ask the person to sign in again
  before an action that needs it. It is `null` for anything but a browser session, since an API
  key or a bearer token stands for no sign-in.
- `credentials` counts the identity's sign-in credentials by kind (`email`, `passkey`, `account`),
  for each sign-in module present; `account` counts only the linked accounts a configured provider
  registration signs in (see [the last-credential rule](#the-last-credential-rule)). A page can use it to hide a Remove button on the last one. It is
  empty when who cannot count, which happens when there is no single `PlatformTransactionManager`
  (see [the last-credential rule](#the-last-credential-rule)).

## What a sign-in page can offer

`GET /who/sign-in` is public and says nothing about any identity. It tells a signed-out page which
buttons to draw:

```json
{ "email": true, "passkey": true, "providers": [{ "id": "github", "name": "GitHub" }] }
```

`email` and `passkey` are `false` when their module is absent or turned off. `providers` lists the
[provider registrations](social-login.md) who signs in with, in the order they are configured; `id`
is the registration id, which `GET /who/oidc/authorize/{id}` takes, and `name` is the
registration's `client-name` (Spring uses the id when none is set). who's default chain opens it to
signed-out requests, and it is in `WhoPublicPaths` for a chain of your own.

## Sessions

`GET /who/me/sessions`, `DELETE /who/me/sessions/{id}` and `DELETE /who/me/sessions?others=true`:
see [Sessions](sessions.md#see-and-end-sessions).

## Passkeys

`GET /who/me/passkeys` lists the caller's passkeys, oldest first:

```json
[{ "credentialId": "q3JzT1...", "label": "Laptop", "createdAt": "2026-11-16T09:12:44Z", "lastUsedAt": "2026-11-17T08:00:01Z" }]
```

`credentialId` is the WebAuthn credential id in base64url, which names the passkey in
`DELETE /who/me/passkeys/{credentialId}`. who's internal id is not shown. `lastUsedAt` is `null` for
a passkey that has never signed in.

Registering one is `POST /who/me/passkeys/options` and then `POST /who/me/passkeys`; see
[Passkeys](passkeys.md#register-a-passkey). Removing the passkey a session signed in with ends that
session.

## Email addresses

`GET /who/me/email-addresses` lists the caller's addresses, oldest first:

```json
[{ "id": "0193f2a4-...", "address": "alice@example.com", "createdAt": "2026-11-16T09:12:44Z" }]
```

`id` is the email credential's id: `DELETE /who/me/email-addresses/{id}` takes it, and so does
`replaces` when changing an address. `createdAt` is read from the id, which records when it was made.

Adding or changing an address (`POST /who/me/email-addresses`, then
`POST /who/me/email-addresses/confirmations`) is in
[Email Links](email-links.md#adding-or-changing-an-address).

Removing the address a session signed in with is allowed. That session ends, as with any revoked
credential: its next request is `401`, and the person signs in with another credential.

## Linked accounts

`GET /who/me/accounts` lists the provider accounts linked to the caller, oldest first:

```json
[{ "id": "0193f2a4-...", "provider": "github", "linkedAt": "2026-11-16T09:12:44Z" }]
```

`provider` is the registration id whose issuer matches the account's, or the issuer itself when no
registration matches any more. A JWT `(issuer, subject)` linked to the identity is listed too, with
its issuer. `linkedAt` is read from the id.

`POST /who/me/accounts/{registrationId}` starts a link; see
[Social Login](social-login.md#link-an-account-to-someone-signed-in).
`DELETE /who/me/accounts/{id}` unlinks one. Unlinking the account a session signed in with ends
that session.

## API keys

A browser session or a bearer token may manage the caller's keys. A request an API key signed in
gets `403` `api-key-cannot-manage-keys` on all of them but `/current`: a key never lists, makes, rotates or revokes keys, so a
leaked key cannot make itself permanent.

| Request | Body | Answer |
|---|---|---|
| `GET /who/me/api-keys` | | `200` `[{id, name, scopes, createdAt, expiresAt, lastUsedAt}]`, by name |
| `POST /who/me/api-keys` | `{"name": "CI", "lifetime": "P90D", "scopes": ["report.read"]}` | `201` `{id, name, scopes, expiresAt, key}` |
| `POST /who/me/api-keys/{id}/rotations` | `{"overlap": "PT1H"}` | `201` `{id, name, scopes, expiresAt, key}`, the new key |
| `DELETE /who/me/api-keys/{id}` | | `204` |
| `GET /who/me/api-keys/current` | | `200` `{id, name, scopes, createdAt, expiresAt, lastUsedAt}`, the key that signed this request in |

- `key`, the secret, is in the two `201` answers and nowhere else: not in the list, not in
  `/current`, not in a log line. Show it to the person once.
- `lifetime` and `overlap` are ISO-8601 durations. `overlap` is required; the old key keeps
  working for that long. The limits are those of [`ApiKeyService`](api-keys.md): the lifetime at
  most `who.api-key.max-lifetime`, the overlap at most 7 days, and only scopes the owner holds.
- `lastUsedAt` is `null` for a key never used, and lags by up to `who.api-key.last-used-interval`.
- A browser needs a recent sign-in to make or rotate a key. A bearer token does not: its own
  lifetime bounds it.
- `/current` takes the key from the sign-in, never from the request, so a machine can read its own
  expiry and nothing else. Without an API key it is `404`.
- An id that is not one of the caller's keys, unknown or malformed, is `404`
  (`ApiKeyNotFoundException`). A body who refuses (not JSON, a missing field, a duration it cannot
  read or that is out of range, a blank name, no scopes or a scope the owner lacks, or a key that expired or was
  revoked before its rotation) is `400` (`InvalidApiKeyRequestException`).

## The last-credential rule

Removing a passkey, an email address or a linked account through `/who/me` answers `409`
`LastCredentialException` (`Who-Error: last-credential`) when it would leave the identity with no
way to sign in. Email addresses, passkeys and linked accounts count; API keys do not, because they
sign in a machine, not the person.

The count and the removal happen in one transaction that locks the identity's row
(`SELECT ... FOR UPDATE`), so two removals at once cannot both pass the check. The order of checks
on a removal is: signed out (`401`), not a browser (`403`), not recent (`401`), not the caller's
(`404`), the last credential (`409`).

Only a linked account that a configured provider registration signs in counts: one whose
issuer is the issuer of a [registration](social-login.md) who signs in with today. An account
whose provider was removed from the configuration, and an `(issuer, subject)` a [JWT](jwt.md)
signs in with, are not counted, since no one can sign in with them at `/who/oidc`; without
`who-oidc`, or with `who.oidc.enabled=false`, no linked account counts. Unlinking such an account
is never refused: it cannot be the last way in. Email addresses and passkeys count whenever their
module is on.

The services keep their contract: `PasskeyService.revoke`, `EmailCredentialService.revoke` and
`FederatedCredentialService.unlink` check neither ownership nor this rule. Code of yours that
removes a credential on a person's behalf applies the rule with the `LastCredentialGuard` bean:

```java
guard.removeIfNotLast(identityId, () -> {
  passkeyService.revoke(passkeyId);
  return null;
});
```

The guard needs `who-jdbc` and exactly one `PlatformTransactionManager` bean (Spring Boot makes one
for a single `DataSource`). `who-passkey`, `who-email-link` and `who-oidc` need the guard, so with
any of them on, an application without a single transaction manager fails startup with
`NoSuchBeanDefinitionException` for `LastCredentialGuard`. Name one manager `@Primary`, or define
your own `LastCredentialGuard` bean.
