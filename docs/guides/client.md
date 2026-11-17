# TypeScript Client

`@jwcarman/who-client` is a typed client for who's browser endpoints: sign-in by email link or code, passkeys and providers, sign-out, the caller's own account under `/who/me` (sessions, passkeys, email addresses, linked accounts, API keys), and who's [admin endpoints](admin.md). It handles who's CSRF cookie, the WebAuthn encoding and the tokens in links, and each call answers a typed result instead of a status code.

Each release of who publishes the client to npm with the same version.

## Install

With a bundler:

```bash
npm install @jwcarman/who-client
```

A page without a bundler can import the built module from jsDelivr, pinned to your who version:

```html
<script type="module">
  import { createWhoClient } from 'https://cdn.jsdelivr.net/npm/@jwcarman/who-client@0.20.0/dist/index.js';
</script>
```

Or serve it yourself: the package is one file, `dist/index.js`. who's examples copy it into their static resources at build time.

## Create a client

```ts
import { createWhoClient } from '@jwcarman/who-client';

const who = createWhoClient();                      // who at /who, the default who.base-path
const who = createWhoClient({ baseUrl: '/auth' });  // who.base-path=/auth
```

| Option | What it does |
|---|---|
| `baseUrl` | Where who is: relative to the page (default `/who`) or absolute. A trailing `/` is dropped; a query or fragment is refused with an error. |
| `decodeError` | Turns a failed response's body into your application's error type (below). |
| `fetch` | Replaces the global `fetch`, for tests or instrumentation. |
| `navigate` | Sends the page to another site, for `me.accounts.link`. Defaults to `location.assign`. |

Create one client and share it: it keeps no state between calls.

### Same origin

who protects its endpoints with Spring Security's `csrf.spa()`: the page reads the `XSRF-TOKEN` cookie and sends it back in the `X-XSRF-TOKEN` header. The client does this on every unsafe request, reads the cookie fresh each time (it changes at sign-in and sign-out), and fetches one with a `GET <baseUrl>/me` when there is none yet.

So the page must be able to read who's cookie. Serve the page and who from the same origin, as every example does. An absolute `baseUrl` on another host works only when that host is on the same site and the cookie's domain covers both; the client then sends cookies with `credentials: 'include'`.

## Results

A call that can fail in an expected way answers a result rather than throwing:

```ts
type WhoResult<T, R extends string, D> =
  | { ok: true; value: T }
  | { ok: false; reason: R; status: number; detail: D };
```

`reason` is a string union per call, so the compiler knows every way a call can fail, and narrows on `ok` and `reason`:

```ts
const result = await who.me.sessions.endOthers();
if (!result.ok) {
  switch (result.reason) {
    case 'reauthentication-required':
      return show('Sign in again to sign out your other browsers.');
    case 'signed-out':
    case 'sign-in-required':
      return goToSignIn();
  }
}
show(`Signed out ${result.value.ended} other browsers.`);
```

Prefer exceptions? `unwrap(result)` gives the value or throws a `WhoError` carrying `reason`, `status` and `detail`:

```ts
import { unwrap } from '@jwcarman/who-client';

const sessions = unwrap(await who.me.sessions.list());
```

The client throws, never answers, for what a call does not expect: a network failure (the original error), any other status, `5xx` included, and a success body it cannot read (a `WhoError` with reason `'unexpected'`). An error thrown by your `decodeError` is passed through as it is.

The passkey calls also answer `{ ok: false, reason: 'cancelled', status: 0 }` when the person dismisses the browser's prompt, and `me.passkeys.register` answers `'already-registered'` when this device already holds a passkey for the account; nothing reached who, so there is no status or detail. Any other browser failure, such as a page on an origin who does not allow, is thrown.

### Your error body: `decodeError`

who does not write error bodies: it hands each failure to your application's exception handling (see [Errors](errors.md)), so the body is yours. `decodeError` reads it, and its return type becomes the type of every result's `detail`:

```ts
interface ProblemDetail {
  type: string;
  title: string;
  status: number;
  detail?: string;
}

const who = createWhoClient({
  decodeError: async (response) => (await response.json()) as ProblemDetail,
});

const result = await who.email.signIn({ email, code });
if (!result.ok) show(result.detail.title); // a ProblemDetail
```

Without it, `detail` is the body parsed as JSON, or `undefined` when there is none or it is not JSON.

### The `Who-Error` header

A `401` from a call that needs a recent sign-in means either "signed out" or "signed in too long ago". The client tells them apart by who's [`Who-Error` header](errors.md#the-who-error-header), not by your body: `'signed-out'` or `'reauthentication-required'`.

If your error handling clears the header (an advice that calls `response.reset()`), or a page on another origin cannot read it because your CORS configuration does not expose it, such a `401` answers `'sign-in-required'`. `me.get()` then tells the two apart: it answers `'signed-out'` when no one is signed in.

Where one status has two meanings, such as a `403` for a missing permission or a role the caller may not grant, the header decides too; without it the client takes the more common one (`'permission-required'`, `'identity-not-found'`, `'invalid-admin-request'`). A `409` on removing a credential is `'last-credential'` only when the header says so; any other `409` there is thrown as `'unexpected'`. A `403` is `'browser-session-required'` (or, at the API-key calls, `'api-key-cannot-manage-keys'`) only when the header says so: elsewhere in your account it is thrown as `'unexpected'`, and at an admin change it is `'permission-required'`.

## Calls

Paths are below `baseUrl`. A call that needs a recent sign-in lists `'signed-out'`, `'reauthentication-required'` and `'sign-in-required'`, shortened here to *sign-in*. `'browser-session-required'` is shortened to *browser*.

### Signing in and out

| Call | Endpoint | Value | Reasons |
|---|---|---|---|
| `signInOptions()` | `GET /sign-in` | `{ email, passkey, providers }` | (none) |
| `signOut()` | `POST /logout` | (throws on failure) | (none) |
| `email.send(email, options?)` | `POST /email/send` | none | `'human-check-failed'` |
| `email.signIn({ token } \| { email, code }, options?)` | `POST /email/sign-in` | `{ identityId }` | `'rejected'`, `'human-check-failed'` |
| `email.signInFromLocation(options?)` | the same | a result, or `null` with no `#token=` | the same |
| `passkeys.supported()` | (none) | `boolean` | (none) |
| `passkeys.signIn()` | `POST /passkey/sign-in/options`, `POST /passkey/sign-in` | `{ identityId }` | `'rejected'`, `'cancelled'` |
| `oidc.authorizeUrl(registrationId)` | (builds the link) | `string` | (none) |
| `oidc.pending()` | `GET /oidc/pending` | `PendingSignUp` | `'none-pending'` |
| `oidc.signUp()` | `POST /oidc/pending/sign-up` | `{ identityId }` | `'refused'`, `'none-pending'` |
| `oidc.link()` | `POST /oidc/pending/link` | none | *sign-in*, `'conflict'`, `'none-pending'` |

### Your account

| Call | Endpoint | Value | Reasons |
|---|---|---|---|
| `me.get()` | `GET /me` | `Me` | `'signed-out'` |
| `me.sessions.list()` | `GET /me/sessions` | `Session[]` | `'signed-out'`, *browser* |
| `me.sessions.end(id)` | `DELETE /me/sessions/{id}` | none | *sign-in*, *browser*, `'not-found'` |
| `me.sessions.endOthers()` | `DELETE /me/sessions?others=true` | `{ ended }` | *sign-in*, *browser* |
| `me.passkeys.list()` | `GET /me/passkeys` | `Passkey[]` | `'signed-out'`, *browser* |
| `me.passkeys.register(label)` | `POST /me/passkeys/options`, `POST /me/passkeys` | none | *sign-in*, *browser*, `'rejected'`, `'cancelled'`, `'already-registered'` |
| `me.passkeys.remove(credentialId)` | `DELETE /me/passkeys/{credentialId}` | none | *sign-in*, *browser*, `'not-found'`, `'last-credential'` |
| `me.emailAddresses.list()` | `GET /me/email-addresses` | `EmailAddress[]` | `'signed-out'` |
| `me.emailAddresses.add(address, replaces?)` | `POST /me/email-addresses` | none | *sign-in*, *browser*, `'not-found'` |
| `me.emailAddresses.confirm({ token } \| { code })` | `POST /me/email-addresses/confirmations` | `{ credentialId }` | *sign-in*, *browser*, `'rejected'`, `'conflict'` |
| `me.emailAddresses.confirmFromLocation()` | the same | a result, or `null` with no `#confirm-address=` | the same |
| `me.emailAddresses.remove(id)` | `DELETE /me/email-addresses/{id}` | none | *sign-in*, *browser*, `'not-found'`, `'last-credential'` |
| `me.accounts.list()` | `GET /me/accounts` | `LinkedAccount[]` | `'signed-out'` |
| `me.accounts.link(registrationId)` | `POST /me/accounts/{registrationId}` | (leaves the page) | *sign-in*, *browser*, `'not-found'` |
| `me.accounts.unlink(id)` | `DELETE /me/accounts/{id}` | none | *sign-in*, *browser*, `'not-found'`, `'last-credential'` |
| `me.apiKeys.list()` | `GET /me/api-keys` | `ApiKey[]` | `'signed-out'`, *browser*, `'api-key-cannot-manage-keys'` |
| `me.apiKeys.create({ name, lifetime, scopes })` | `POST /me/api-keys` | `NewApiKey` | *sign-in*, *browser*, `'api-key-cannot-manage-keys'`, `'invalid-api-key-request'` |
| `me.apiKeys.rotate(id, overlap)` | `POST /me/api-keys/{id}/rotations` | `NewApiKey` | *sign-in*, *browser*, `'api-key-cannot-manage-keys'`, `'not-found'`, `'invalid-api-key-request'` |
| `me.apiKeys.revoke(id)` | `DELETE /me/api-keys/{id}` | none | `'signed-out'`, *browser*, `'api-key-cannot-manage-keys'`, `'not-found'` |
| `me.apiKeys.current()` | `GET /me/api-keys/current` | `ApiKey` | `'signed-out'`, `'not-found'` |

### Admin

Each needs a [permission](admin.md#permissions): `who.identities.read` for the reads,
`who.identities.manage` for the rest.

| Call | Endpoint | Value | Reasons |
|---|---|---|---|
| `admin.identities.list(query?)` | `GET /identities` | `IdentityPage` | `'signed-out'`, `'permission-required'`, `'invalid-admin-request'` |
| `admin.identities.get(id)` | `GET /identities/{id}` | `AdminIdentity` | `'signed-out'`, `'permission-required'`, `'identity-not-found'` |
| `admin.identities.setStatus(id, 'ACTIVE' \| 'DISABLED')` | `PATCH /identities/{id}` | `AdminIdentity` | *sign-in*, *browser*, `'permission-required'`, `'identity-outranks-caller'`, `'identity-not-found'`, `'invalid-admin-request'`, `'self-deactivation'`, `'last-admin'` |
| `admin.identities.addRole(id, role)` | `PUT /identities/{id}/roles/{role}` | none | *sign-in*, *browser*, `'permission-required'`, `'identity-outranks-caller'`, `'role-grant-forbidden'`, `'identity-not-found'`, `'unknown-role'` |
| `admin.identities.removeRole(id, role)` | `DELETE /identities/{id}/roles/{role}` | none | *sign-in*, *browser*, `'permission-required'`, `'identity-outranks-caller'`, `'identity-not-found'`, `'unknown-role'`, `'last-admin'` |
| `admin.roles.list()` | `GET /roles` | `AdminRole[]` | `'signed-out'`, `'permission-required'` |
| `admin.invite(email, roles?)` | `POST /invitations` | `{ identityId, linkSent }` | *sign-in*, *browser*, `'permission-required'`, `'role-grant-forbidden'`, `'invalid-invitation'`, `'invalid-admin-request'`, `'email-address-taken'` |

`list`'s `query` takes `status`, `role`, `email`, `page` and `size`; only those given are sent.

What the reasons mean:

| Reason | When |
|---|---|
| `'rejected'` | A wrong, used or expired code, link or passkey. |
| `'not-found'` | No such session, passkey, address, account, key or provider, or one that is someone else's. |
| `'conflict'` | The address or provider account belongs to someone else. |
| `'refused'` | Your sign-up policy said no. |
| `'none-pending'` | No provider account is waiting: it expired, or there never was one. |
| `'last-credential'` | Removing it would leave you no way to sign in. |
| `'browser-session-required'` | Only a browser session on its own may do this: the request carried an API key or a bearer token, perhaps beside the session cookie. |
| `'api-key-cannot-manage-keys'` | A request an API key signed in may not manage keys. |
| `'invalid-api-key-request'` | who refused the key's name, lifetime, overlap or scopes; `detail` says why. |
| the admin codes | The [`Who-Error` codes](errors.md#the-who-error-header) of the same names. |

### Email links

`email.send` answers the same whether or not the address is known, so it tells an attacker nothing. The link in the email puts its token in the URL fragment, which never reaches a server. On the page the link opens, `email.signInFromLocation()` takes the token out of the address bar and history first, then signs in; `me.emailAddresses.confirmFromLocation()` does the same for an address confirmation. See [Email Links](email-links.md).

When your application has a [human check](email-links.md#human-check), pass each call a fresh token from its widget: `{ humanCheck: token }`. The client sends it in the `Who-Human-Check` header. A refusal answers `'human-check-failed'`; show the widget again and retry.

### Passkeys

`me.passkeys.register` asks who first, so a person who signed in too long ago gets `'reauthentication-required'` before the browser prompts. `me.passkeys.remove` takes the `credentialId` that `me.passkeys.list()` shows. Removing the passkey a session signed in with signs that session out. See [Passkeys](passkeys.md).

### Provider sign-in

A provider's sign-in button is a plain link to `oidc.authorizeUrl(id)`: a full page load, with no CSRF. who sends the browser back to your page, with `?pending=sign-up` or `?pending=link` when it needs the person to confirm; `oidc.pending()` describes the account for that page, and `oidc.signUp()` or `oidc.link()` confirms it. Call `oidc.link()` only after the person confirms.

`me.accounts.link(id)` asks who where the provider is, then sends the page there, so on success its promise never settles; who sends the browser back with `?pending=link`, and `oidc.link()` finishes it. See [Social Login](social-login.md).

### Sessions

`Session` is `{ id, device, userAgent, signInMethod, signedInAt, lastSeenAt, current }`. Ending this browser's own session needs no recent sign-in; ending another does. See [Sessions](sessions.md).

### Your account in one page

`me.get()` answers `Me`: `{ identityId, permissions, signInMethod, recentSignInUntil, credentials }`. `recentSignInUntil` says until when the actions marked *sign-in* above will work without signing in again; `credentials` counts the sign-in credentials by kind, so a page can hide Remove on the last one. Each `me.*` namespace lists, adds and removes one kind of thing; see [Your Account](account.md) for who may call each and what each field means.

`me.apiKeys.create` and `me.apiKeys.rotate` answer the raw key in `NewApiKey.key`, once: show it, and never store it in the page.

