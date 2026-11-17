# TypeScript Client

`@jwcarman/who-client` is a typed client for who's browser endpoints: sign-in by email link or code, passkeys, provider sign-in and linking, sessions and sign-out. It handles who's CSRF cookie, the WebAuthn encoding and the tokens in links, and each call answers a typed result instead of a status code.

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
| `navigate` | Sends the page to another site, for `oidc.startLink`. Defaults to `location.assign`. |

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
const result = await who.sessions.endOthers();
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

const sessions = unwrap(await who.sessions.list());
```

The client throws, never answers, for what a call does not expect: a network failure (the original error), any other status, `5xx` included, and a success body it cannot read (a `WhoError` with reason `'unexpected'`). An error thrown by your `decodeError` is passed through as it is.

The passkey calls also answer `{ ok: false, reason: 'cancelled', status: 0 }` when the person dismisses the browser's prompt, and `passkeys.register` answers `'already-registered'` when this device already holds a passkey for the account; nothing reached who, so there is no status or detail. Any other browser failure, such as a page on an origin who does not allow, is thrown.

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

If your error handling clears the header (an advice that calls `response.reset()`), or a page on another origin cannot read it because your CORS configuration does not expose it, such a `401` answers `'sign-in-required'`. `me()` then tells the two apart: it answers `'signed-out'` when no one is signed in.

## Calls

Paths are below `baseUrl`. A call that needs a recent sign-in lists `'signed-out'`, `'reauthentication-required'` and `'sign-in-required'`, shortened here to *sign-in*.

| Call | Endpoint | Value | Reasons |
|---|---|---|---|
| `me()` | `GET /me` | `{ identityId, permissions }` | `'signed-out'` |
| `signOut()` | `POST /logout` | (throws on failure) | (none) |
| `sessions.list()` | `GET /sessions` | `Session[]` | `'signed-out'` |
| `sessions.end(id)` | `DELETE /sessions/{id}` | none | *sign-in*, `'not-found'` |
| `sessions.endOthers()` | `DELETE /sessions?others=true` | `{ ended }` | *sign-in* |
| `email.send(email, options?)` | `POST /email/send` | none | `'human-check-failed'` |
| `email.signIn({ token } \| { email, code }, options?)` | `POST /email/sign-in` | `{ identityId }` | `'rejected'`, `'human-check-failed'` |
| `email.signInFromLocation(options?)` | the same | a result, or `null` with no `#token=` | the same |
| `email.addAddress(address, replaces?)` | `POST /email/addresses` | none | *sign-in*, `'not-found'` |
| `email.confirmAddress({ token } \| { code })` | `POST /email/addresses/confirm` | none | *sign-in*, `'rejected'`, `'conflict'` |
| `email.confirmAddressFromLocation()` | the same | a result, or `null` with no `#confirm-address=` | the same |
| `passkeys.supported()` | (none) | `boolean` | (none) |
| `passkeys.register(label)` | `POST /passkey/register/options`, `POST /passkey/register` | none | *sign-in*, `'rejected'`, `'cancelled'`, `'already-registered'` |
| `passkeys.signIn()` | `POST /passkey/sign-in/options`, `POST /passkey/sign-in` | `{ identityId }` | `'rejected'`, `'cancelled'` |
| `passkeys.remove(credentialId)` | `DELETE /passkey/register/{id}` | none | `'signed-out'`, `'refused'` |
| `oidc.authorizeUrl(registrationId)` | (builds the link) | `string` | (none) |
| `oidc.pending()` | `GET /oidc/pending` | `PendingSignUp` | `'none-pending'` |
| `oidc.signUp()` | `POST /oidc/sign-up` | `{ identityId }` | `'refused'`, `'none-pending'` |
| `oidc.link()` | `POST /oidc/link` | none | *sign-in*, `'conflict'`, `'none-pending'` |
| `oidc.startLink(registrationId)` | `POST /oidc/link/{id}` | (leaves the page) | *sign-in*, `'not-found'` |

What the reasons mean:

| Reason | When |
|---|---|
| `'rejected'` | A wrong, used or expired code, link or passkey. |
| `'not-found'` | No such session, address, or provider. |
| `'conflict'` | The address or provider account belongs to someone else. |
| `'refused'` | Someone else's passkey, or your sign-up policy said no. |
| `'none-pending'` | No provider account is waiting: it expired, or there never was one. |

### Email links

`email.send` answers the same whether or not the address is known, so it tells an attacker nothing. The link in the email puts its token in the URL fragment, which never reaches a server. On the page the link opens, `email.signInFromLocation()` takes the token out of the address bar and history first, then signs in; `email.confirmAddressFromLocation()` does the same for an address confirmation. See [Email Links](email-links.md).

When your application has a [human check](email-links.md#human-check), pass each call a fresh token from its widget: `{ humanCheck: token }`. The client sends it in the `Who-Human-Check` header. A refusal answers `'human-check-failed'`; show the widget again and retry.

### Passkeys

`passkeys.register` asks who first, so a person who signed in too long ago gets `'reauthentication-required'` before the browser prompts. `passkeys.remove` takes the passkey's WebAuthn credential id (`PasskeySummary.credentialId`), not who's id. Removing the passkey a session signed in with signs that session out. See [Passkeys](passkeys.md).

### Provider sign-in

A provider's sign-in button is a plain link to `oidc.authorizeUrl(id)`: a full page load, with no CSRF. who sends the browser back to your page, with `?pending=sign-up` or `?pending=link` when it needs the person to confirm; `oidc.pending()` describes the account for that page, and `oidc.signUp()` or `oidc.link()` confirms it. Call `oidc.link()` only after the person confirms.

`oidc.startLink(id)` asks who where the provider is, then sends the page there, so on success its promise never settles. See [Social Login](social-login.md).

### Sessions

`Session` is `{ id, device, userAgent, signInMethod, signedInAt, lastSeenAt, current }`. Ending this browser's own session needs no recent sign-in; ending another does. See [Sessions](sessions.md).
