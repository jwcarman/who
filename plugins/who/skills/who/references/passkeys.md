# Passkeys

**Contents**

- Turn it on
- Wire the chain
- The browser flow
- Listing and revoking passkeys
- The name on the passkey picker
- The "passkey added" email
- Trust and recovery
- Database

Who supports sign-in with passkeys, in the `who-passkey` module (`org.jwcarman.who.passkey`). It is built on Spring Security's WebAuthn support, with Spring's filters moved under `who.base-path` (default `/who`): `POST /who/passkey/sign-in/options` and `POST /who/passkey/sign-in` to sign in, and `POST /who/me/passkeys/options` and `POST /who/me/passkeys` to register one. Who adds `GET /who/me/passkeys` and `DELETE /who/me/passkeys/{credentialId}` for a person's own list, supplies the hooks Spring leaves fixed, and stores passkeys in its own tables.

A passkey is the only factor. Who requires user verification (a PIN, a fingerprint or a face scan) and a discoverable (resident) credential, so the user does not type a name.

Add `who-passkey` to turn passkeys on; an application without the jar has none of this.

## Turn it on

Add the dependency (its version comes from who's BOM):

```xml
<dependency>
    <groupId>org.jwcarman.who</groupId>
    <artifactId>who-passkey</artifactId>
</dependency>
```

Then set the relying party id: the host of your app. Startup fails until it is set; the message
also says how to turn passkeys off without removing the jar: `who.passkey.enabled=false`.

```yaml
who:
  passkey:
    rp-id: app.example.com
```

The defaults are `rp-name` `who` and `allowed-origins` `https://<rp-id>`:

```yaml
who:
  passkey:
    rp-id: app.example.com
    rp-name: Example App
    allowed-origins:
      - https://app.example.com
```

Each `allowed-origins` entry is an origin: a scheme (`http` or `https`), a host and an optional port, with no path or trailing slash, as the browser reports it. A blank `rp-name`, or an entry such as `app.example.com` or `https://app.example.com/signin`, fails startup with a message that names the property.

For local development, the default origin `https://<rp-id>` does not match the page. Set the origin explicitly, with `rp-id` `localhost` (browsers allow WebAuthn over plain `http` on `localhost` only):

```yaml
who:
  passkey:
    rp-id: localhost
    allowed-origins:
      - http://localhost:8080
```

Use one `rp-id` per app host. Never use a registrable domain that other apps share (for example `example.com` for several apps under it). Every app's passkeys would then appear in every other app's picker.

Passkey storage is PostgreSQL only, because the repositories use `ON CONFLICT`. It needs PostgreSQL 14+. See [Database Setup](database.md).

See the [Configuration Reference](configuration.md) for all five properties.

## Wire the chain

With no `SecurityFilterChain` of your own there is nothing to wire: who's default chain mounts
passkeys and opens `/who/passkey/sign-in/options` and `/who/passkey/sign-in` to signed-out requests
(see [Getting Started](getting-started.md#secure-the-app)). List your own sign-in page in
`who.security.public-paths`.

### Your own chain

In a chain of your own, `WhoSecurity.all()` mounts every sign-in method at once (see [Getting
Started](getting-started.md#your-own-chain)). Or use the same `WhoWebSecurity.passwordless()` as
[Email Links](email-links.md): apply it to the chain that serves your browser app and permit the
sign-in endpoints:

```java
@Bean
SecurityFilterChain browserChain(HttpSecurity http) throws Exception {
    http.with(WhoWebSecurity.passwordless(), Customizer.withDefaults());
    http.authorizeHttpRequests(a -> a
            .requestMatchers("/who/passkey/sign-in/options", "/who/passkey/sign-in", "/signin").permitAll()
            .anyRequest().authenticated());
    return http.build();
}
```

`passwordless()` adds Spring Security's passkey filters to the chain itself, at who's paths, without Spring's `webAuthn()` (which fixes the paths) and without a registration page. Do not also call `webAuthn()` on the chain. It also makes the session id change at sign-in and rotates the CSRF token, which Spring's WebAuthn filter does not do by default. Everything `passwordless()` does for email links applies here too (see [Email Links](email-links.md#wire-the-chain)).

The endpoints under `/who/me/passkeys` need a signed-in browser session, but `anyRequest().authenticated()` does not protect all of them. Spring runs the filter for `POST /who/me/passkeys/options` before the authorization filter, so your rules never see it. Who's `PasskeyRegistrationGuardFilter` covers it, and `POST /who/me/passkeys`, with a `401` for a caller who is signed out (`NotSignedInException`) or signed in too long ago (`ReauthenticationRequiredException`), and a `403` for an API key or bearer token on the request, even beside a session cookie; your application formats them (see [Errors](errors.md)). Each endpoint is protected like this:

- `POST /who/me/passkeys/options`: by who's guard only. Your `authorizeHttpRequests` rules do not apply.
- `POST /who/me/passkeys`: by your rules (leave it behind `authenticated()`) and by who's guard.
- `GET /who/me/passkeys` and `DELETE /who/me/passkeys/{credentialId}`: by who's own filter, which runs before your rules and answers a signed-out caller with `401` itself. Spring's own delete endpoint is turned off.

Do not put these paths under `permitAll()`.

If your app defines its own `PublicKeyCredentialUserEntityRepository` or `UserCredentialRepository` bean for another chain that calls `webAuthn()`, that chain falls back to in-memory storage, because Spring finds two beans of one type. Who's chain is not affected: who hands its own repositories to its passkey filters directly.

## The browser flow

The [TypeScript client](client.md#passkeys) does all of this, the encoding included: `who.passkeys.signIn`, and `who.me.passkeys.register`, `who.me.passkeys.list` and `who.me.passkeys.remove`.

All requests are JSON. Every POST needs the CSRF token in the `X-XSRF-TOKEN` header, as described in [Email Links](email-links.md#the-sign-in-flow). The examples use the same `post` helper:

```javascript
function xsrf() {
  return document.cookie.split('; ')
    .find(c => c.startsWith('XSRF-TOKEN='))?.split('=')[1];
}

async function post(path, body) {
  return fetch(path, {
    method: 'POST',
    credentials: 'same-origin',
    headers: {
      'Content-Type': 'application/json',
      'X-XSRF-TOKEN': decodeURIComponent(xsrf() ?? '')
    },
    body: body === undefined ? undefined : JSON.stringify(body)
  });
}

// Spring's options are JSON with base64url strings. The browser wants bytes.
const b64u = {
  decode: s => Uint8Array.from(atob(s.replace(/-/g, '+').replace(/_/g, '/')), c => c.charCodeAt(0)),
  encode: b => btoa(String.fromCharCode(...new Uint8Array(b)))
    .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
};
```

### Register a passkey

The user must be signed in, and the sign-in must be within `who.security.recent-sign-in` (10 minutes by default), the window every way of adding a credential shares. The usual path is: sign in by email link, then add a passkey.

Passkeys cannot be an app's only way in. Registering needs a recent who session, and in who only [Email Links](email-links.md) create the first one: passkey sign-in needs a passkey that is already registered. An app that turns on passkeys alone has no way for a new user to register one. Turn on email links as well, or create the first session yourself with your own `PasswordlessLoginMethod`.

```javascript
async function registerPasskey(label) {
  const optionsRes = await post('/who/me/passkeys/options');
  if (!optionsRes.ok) throw new Error('options ' + optionsRes.status);
  const options = await optionsRes.json();

  options.challenge = b64u.decode(options.challenge);
  options.user.id = b64u.decode(options.user.id);
  options.excludeCredentials = (options.excludeCredentials ?? [])
    .map(c => ({ ...c, id: b64u.decode(c.id) }));

  const credential = await navigator.credentials.create({ publicKey: options });

  const res = await post('/who/me/passkeys', {
    publicKey: {
      credential: {
        id: credential.id,
        rawId: b64u.encode(credential.rawId),
        type: credential.type,
        response: {
          attestationObject: b64u.encode(credential.response.attestationObject),
          clientDataJSON: b64u.encode(credential.response.clientDataJSON),
          transports: credential.response.getTransports?.() ?? []
        },
        clientExtensionResults: credential.getClientExtensionResults(),
        authenticatorAttachment: credential.authenticatorAttachment
      },
      label
    }
  });
  return res.status; // 200 with {"success":true}
}
```

The `label` is required (the `label` column is `NOT NULL`). It is the name the user sees in your passkey list, such as "Laptop".

The answers. An error's body is whatever your application writes for its exception (see [Errors](errors.md)):

| Status | Body | Meaning |
|--------|------|---------|
| `200` | `{"success":true}` | The passkey is registered. |
| `401` | yours | `NotSignedInException`: the caller is signed out. |
| `401` | yours | `ReauthenticationRequiredException`: the sign-in is older than `who.security.recent-sign-in`. Send the user through an email link again, then retry. |
| `403` | yours | An API key or a bearer token on the request: only a browser session registers a passkey. |
| `400` | yours | `InvalidPasskeyRegistrationException`: the response failed verification: a wrong origin or challenge, or a device that did not verify the user. Also when the options in the session were requested by a different identity than the one signed in now (someone else signed in on the same browser before the registration finished). |
| `400` | empty | Spring's own answer to a malformed registration body, or to a request with no options in the session (post to `/who/me/passkeys/options` first). |

### Sign in with a passkey

Sign-in needs no session. The user does not type anything: the browser shows the passkeys it holds for the `rp-id`.

```javascript
async function signInWithPasskey() {
  const optionsRes = await post('/who/passkey/sign-in/options');
  const options = await optionsRes.json();

  options.challenge = b64u.decode(options.challenge);
  options.allowCredentials = (options.allowCredentials ?? [])
    .map(c => ({ ...c, id: b64u.decode(c.id) }));

  const credential = await navigator.credentials.get({ publicKey: options });

  const res = await post('/who/passkey/sign-in', {
    id: credential.id,
    rawId: b64u.encode(credential.rawId),
    type: credential.type,
    response: {
      authenticatorData: b64u.encode(credential.response.authenticatorData),
      clientDataJSON: b64u.encode(credential.response.clientDataJSON),
      signature: b64u.encode(credential.response.signature),
      userHandle: credential.response.userHandle
        ? b64u.encode(credential.response.userHandle) : null
    },
    clientExtensionResults: credential.getClientExtensionResults(),
    authenticatorAttachment: credential.authenticatorAttachment
  });
  if (res.ok) {
    const { identityId } = await res.json();
  }
}
```

On success the answer is `200` with `{"identityId": "..."}` and a session cookie. Every failure is a `SignInFailedException` (`401` unless your application maps it otherwise; see [Errors](errors.md)): an unknown passkey, a bad signature, a missing user verification, or an identity that is not active.

The session id changes at sign-in and the CSRF token rotates. With `csrf.spa()` the page must read the `XSRF-TOKEN` cookie again after signing in and after signing out, before the next POST. A page that cached the old value gets `403`.

```javascript
await signInWithPasskey();
const csrf = decodeURIComponent(xsrf()); // read it again, not before sign-in
```

`GET /who/me` and `POST /who/logout` work as for email links.

## Listing and revoking passkeys

A person lists and removes their own passkeys through who's endpoints, on every browser chain:

| Request | Answer |
|---|---|
| `GET /who/me/passkeys` | `200`, `[{credentialId, label, createdAt, lastUsedAt}]`, oldest first |
| `DELETE /who/me/passkeys/{credentialId}` | `204`. The passkey is deleted, with who's identity link, so it cannot sign in again |

Both are for a browser session only (`403` otherwise), and removing needs a recent sign-in (`401`
`ReauthenticationRequiredException`). A passkey that is someone else's, or does not exist, is `404`.
Removing the identity's last way to sign in is `409` (`last-credential`). Removing the passkey a
session signed in with ends that session. See [Your Account](account.md) for the full rules.

From your own code, inject `PasskeyService`:

```java
List<PasskeySummary> passkeys = passkeyService.list(identityId);
passkeyService.revoke(passkeys.get(0).id());
```

`list` returns the identity's passkeys, oldest first, as `PasskeySummary(id, credentialId, label, created, lastUsed)`. `id` is who's credential UUID, which `revoke` takes. `credentialId` is the WebAuthn credential id in base64url, which `DELETE /who/me/passkeys/{credentialId}` takes. `revoke(UUID)` deletes the credential record and unlinks it from the identity. It does nothing if no passkey has that id. `ownerOf(credentialId)` answers the identity a credential id belongs to.

`PasskeyService.revoke` checks neither ownership nor the [last-credential rule](account.md#the-last-credential-rule). It is an application or admin API: check in your own code that the passkey belongs to the caller, and remove it through `LastCredentialGuard` when the person must keep a way in.

## The name on the passkey picker

The browser shows a name next to each passkey. Who does not assume an identity has an email address, so it picks, in order:

1. the address of the email credential the current session signed in with. Registering needs a recent sign-in, usually by email link, so this is the address the user just proved they own;
2. otherwise, the address of the identity's oldest email credential;
3. otherwise, the identity id.

Without `who-email-link` the name is always the identity id.

Who picks the name again each time registration starts (`POST /who/me/passkeys/options`) and updates its own record, so an identity that gains or changes an address gets the new name on its next passkey. The browser stores the name on the user's device when the passkey is created, and devices keep the old name for passkeys they already hold, until browsers support updating it. Sign-in is not affected, because it maps by user handle. The user handle is 64 random bytes. It is not derived from the identity or the address.

The address lands in the user's synced credential manager (for example iCloud Keychain or a password manager). That is the user's own device, and a trade-off to accept.

## The "passkey added" email

When a passkey is registered, who can email the owner, so that someone who did not add a passkey notices. It needs all of:

- `who-email-link` on the classpath and turned on (`who.email-link.link-base-url`);
- a `JavaMailSender` (`spring.mail.host`);
- `who.passkey.from`.

```yaml
spring:
  mail:
    host: smtp.example.com
who:
  email-link:
    link-base-url: https://app.example.com/signin
    code-secret: <at least 32 random characters>
    from: no-reply@example.com
  passkey:
    rp-id: app.example.com
    from: no-reply@example.com
```

`who.passkey.from` does not fall back to `who.email-link.from`. If any of the three is missing, no email is sent and who logs a warning once at startup, at `WARN`, naming what is missing. The warning names properties only, never addresses. An identity with no email address gets no email.

To notify some other way, register a `PasskeyAddedNotifier` bean:

```java
@Bean
PasskeyAddedNotifier passkeyAddedNotifier(MyMailer mailer) {
    return (identityId, label) -> mailer.passkeyAdded(identityId, label);
}
```

It runs only after a successful registration. An exception it throws is logged and does not fail the registration.

## Trust and recovery

Registering a passkey needs a recent sign-in, so the first factor (usually the mailbox) stays the root of trust. A lost device does not lock a user out if the mailbox still works. To remove a lost passkey, the user calls `DELETE /who/me/passkeys/{credentialId}` from another signed-in browser, or an administrator calls `PasskeyService.revoke`.

## Database

The module adds `who_passkey_user` and `who_passkey_credential`. See [Database Setup](database.md).
