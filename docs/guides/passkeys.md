# Passkeys

Who supports sign-in with passkeys, in the `who-passkey` module (`org.jwcarman.who.passkey`). It is built on Spring Security's WebAuthn support and keeps Spring's endpoints at their default paths: `POST /webauthn/register/options`, `POST /webauthn/register`, `DELETE /webauthn/register/{id}`, `POST /webauthn/authenticate/options` and `POST /login/webauthn`. Who supplies the hooks Spring leaves fixed, and stores passkeys in its own tables.

A passkey is the only factor. Who requires user verification (a PIN, a fingerprint or a face scan) and a discoverable (resident) credential, so the user does not type a name.

Passkeys are opt-in. An application that does not set `who.passkey.rp-id` gets no passkey beans.

## Turn it on

Set the relying party id: the host of your app.

```yaml
who:
  passkey:
    rp-id: app.example.com
```

The defaults are `rp-name` `who`, `allowed-origins` `https://<rp-id>` and `registration-window` `10m`:

```yaml
who:
  passkey:
    rp-id: app.example.com
    rp-name: Example App
    allowed-origins:
      - https://app.example.com
    registration-window: 10m
```

Use one `rp-id` per app host. Never use a registrable domain that other apps share (for example `example.com` for several apps under it). Every app's passkeys would then appear in every other app's picker.

Passkey storage is PostgreSQL only, because the repositories use `ON CONFLICT`. It needs PostgreSQL 14+. See [Database Setup](database.md).

See the [Configuration Reference](configuration.md) for all five properties.

## Wire the chain

Passkeys use the same `WhoWebSecurity.passwordless()` as [Email Links](email-links.md). Apply it to the chain that serves your browser app and permit the sign-in endpoints:

```java
@Bean
SecurityFilterChain browserChain(HttpSecurity http) throws Exception {
    http.with(WhoWebSecurity.passwordless(), Customizer.withDefaults());
    http.authorizeHttpRequests(a -> a
            .requestMatchers("/webauthn/authenticate/options", "/login/webauthn", "/signin").permitAll()
            .anyRequest().authenticated());
    return http.build();
}
```

`passwordless()` configures Spring's `webAuthn()` itself, with Spring's default registration page turned off. Do not also call `webAuthn()` on the chain. It also makes the session id change at sign-in and rotates the CSRF token, which Spring's WebAuthn filter does not do by default. Everything `passwordless()` does for email links applies here too (see [Email Links](email-links.md#wire-the-chain)).

`/webauthn/register/options`, `/webauthn/register` and `DELETE /webauthn/register/{id}` need a signed-in caller, so leave them behind `authenticated()`.

If your app has no `UserDetailsService` bean, who supplies one that finds no users. Spring's WebAuthn configurer will not start without one. Who's sign-in never uses it.

If your app defines its own `PublicKeyCredentialUserEntityRepository` or `UserCredentialRepository` bean for another chain that calls `webAuthn()`, that chain falls back to in-memory storage, because Spring finds two beans of one type. Who's chain is not affected: who shares its own repositories with its own chain directly.

## The browser flow

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

The user must be signed in, and the sign-in must be within `registration-window` (10 minutes by default). The usual path is: sign in by email link, then add a passkey.

```javascript
async function registerPasskey(label) {
  const optionsRes = await post('/webauthn/register/options');
  if (!optionsRes.ok) throw new Error('options ' + optionsRes.status);
  const options = await optionsRes.json();

  options.challenge = b64u.decode(options.challenge);
  options.user.id = b64u.decode(options.user.id);
  options.excludeCredentials = (options.excludeCredentials ?? [])
    .map(c => ({ ...c, id: b64u.decode(c.id) }));

  const credential = await navigator.credentials.create({ publicKey: options });

  const res = await post('/webauthn/register', {
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

The answers:

| Status | Body | Meaning |
|--------|------|---------|
| `200` | `{"success":true}` | The passkey is registered. |
| `401` | empty | The caller is signed out. |
| `401` | `{"error":"reauthentication_required"}` | The sign-in is older than `registration-window`. Send the user through an email link again, then retry. |
| `400` | `{"error":"invalid_registration"}` | The response failed verification: a wrong origin or challenge, or a device that did not verify the user. |
| `400` | empty | Spring's own answer to a malformed registration body, or to a request with no options in the session (post to `/webauthn/register/options` first). |

### Sign in with a passkey

Sign-in needs no session. The user does not type anything: the browser shows the passkeys it holds for the `rp-id`.

```javascript
async function signInWithPasskey() {
  const optionsRes = await post('/webauthn/authenticate/options');
  const options = await optionsRes.json();

  options.challenge = b64u.decode(options.challenge);
  options.allowCredentials = (options.allowCredentials ?? [])
    .map(c => ({ ...c, id: b64u.decode(c.id) }));

  const credential = await navigator.credentials.get({ publicKey: options });

  const res = await post('/login/webauthn', {
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

On success the answer is `200` with `{"identityId": "..."}` and a session cookie. Every failure is `401` with `{"error":"invalid_credential"}`: an unknown passkey, a bad signature, a missing user verification, or an identity that is not active.

The session id changes at sign-in and the CSRF token rotates. With `csrf.spa()` the page must read the `XSRF-TOKEN` cookie again after signing in and after signing out, before the next POST. A page that cached the old value gets `403`.

```javascript
await signInWithPasskey();
const csrf = decodeURIComponent(xsrf()); // read it again, not before sign-in
```

`GET /who/me` and `POST /logout` work as for email links.

## Listing and revoking passkeys

Inject `PasskeyService`:

```java
List<PasskeySummary> passkeys = passkeyService.list(identityId);
passkeyService.revoke(passkeys.get(0).id());
```

`list` returns the identity's passkeys, oldest first, as `PasskeySummary(id, label, created, lastUsed)`. `revoke(UUID)` deletes the credential record and unlinks it from the identity. It does nothing if no passkey has that id.

`PasskeyService.revoke` has no ownership check. It is an application or admin API, so check in your own code that the passkey belongs to the caller before you call it.

Spring's `DELETE /webauthn/register/{id}` is the endpoint for users removing their own passkeys. It checks ownership itself and answers `403` for another user's passkey. The `{id}` is the credential id as Spring knows it (base64url), not the UUID. It also removes who's identity link, so a deleted passkey cannot sign in.

## The name on the passkey picker

The browser shows a name next to each passkey. Who does not assume an identity has an email address, so it picks, in order:

1. the address of the email credential the current session signed in with. Registering needs a recent sign-in, usually by email link, so this is the address the user just proved they own;
2. otherwise, the address of the identity's oldest email credential;
3. otherwise, the identity id.

Without `who-email-link` the name is always the identity id.

Who picks the name again each time registration starts (`POST /webauthn/register/options`) and updates its own record, so an identity that gains or changes an address gets the new name on its next passkey. The browser stores the name on the user's device when the passkey is created, and devices keep the old name for passkeys they already hold, until browsers support updating it. Sign-in is not affected, because it maps by user handle. The user handle is 64 random bytes. It is not derived from the identity or the address.

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
    from: no-reply@example.com
  passkey:
    rp-id: app.example.com
    from: no-reply@example.com
```

`who.passkey.from` does not fall back to `who.email-link.from`. If any of the three is missing, no email is sent and who logs the reason once at `INFO` at startup. An identity with no email address gets no email.

To notify some other way, register a `PasskeyAddedNotifier` bean:

```java
@Bean
PasskeyAddedNotifier passkeyAddedNotifier(MyMailer mailer) {
    return (identityId, label) -> mailer.passkeyAdded(identityId, label);
}
```

It runs only after a successful registration. An exception it throws is logged and does not fail the registration.

## Trust and recovery

Registering a passkey needs a recent sign-in, so the first factor (usually the mailbox) stays the root of trust. A lost device does not lock a user out if the mailbox still works. To remove a lost passkey, the user calls `DELETE /webauthn/register/{id}`, or an administrator calls `PasskeyService.revoke`.

## Database

The module adds `who_passkey_user` and `who_passkey_credential`. See [Database Setup](database.md).
