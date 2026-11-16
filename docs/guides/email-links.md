# Email Links

Who supports sign-in by an emailed link or code, in the `who-email-link` module. The user enters an address and gets an email with a link and a short code. Opening the link, or typing the code, signs them in and starts an HTTP session. It is built on Spring Security's one-time-token login. Who supplies the hooks and keeps Spring's endpoints: `POST /who/email/send`, `POST /who/email/sign-in` and `POST /who/logout`. Who adds one endpoint of its own, `GET /who/me`.

Add `who-email-link` to turn email links on; an application without the jar has none of this.

## Turn it on

Add the dependency (its version comes from who's BOM):

```xml
<dependency>
    <groupId>org.jwcarman.who</groupId>
    <artifactId>who-email-link</artifactId>
</dependency>
```

Then set the page of your app that receives sign-in links, and the code secret:

```yaml
who:
  email-link:
    link-base-url: https://app.example.com/signin
    code-secret: <at least 32 random characters>
```

The application also needs a way to send mail. Either register an `EmailLinkSender` bean, or send through Spring Boot's `JavaMailSender`: add `spring-boot-starter-mail` (who-email-link does not bring it) and set `spring.mail.host` and `who.email-link.from`:

```yaml
spring:
  mail:
    host: smtp.example.com
who:
  email-link:
    link-base-url: https://app.example.com/signin
    code-secret: <at least 32 random characters>
    from: no-reply@example.com
```

Startup fails, and the message names the fix, if:

- `link-base-url` is missing or blank;
- `who.email-link.code-length` is below 6;
- `who.email-link.code-max-attempts` is below 1;
- `who.email-link.code-secret` is missing, shorter than 32 characters, or an unresolved placeholder such as `${CODE_SECRET}` (Spring binds an unset variable as that literal text);
- there is no `EmailLinkSender`, and no `JavaMailSender` (from `spring-boot-starter-mail` and `spring.mail.host`) with `who.email-link.from`;
- `who.email-link.resend-interval` is longer than `who.email-link.token-ttl`.

Each message also says how to turn email links off without removing the jar:
`who.email-link.enabled=false`.

A custom sender is one method, which takes any of who's three emails. `WhoEmail` is sealed, so a `switch` covers them all and the compiler tells you when who adds one:

```java
@Bean
EmailLinkSender emailLinkSender(MyMailer mailer) {
    return email -> {
        switch (email) {
            case EmailLinkMessage m -> mailer.send(m.address(), "Your sign-in link and code",
                "Sign in: " + m.link()
                    + "\n\nOr enter this code: " + m.formattedCode()
                    + "\n\nIf you didn't ask to sign in, ignore this email."
                    + " Never share this code with anyone.");
            case EmailAddressConfirmationMessage m -> mailer.send(m.address(), "Confirm your email address",
                "Add this address to your account: " + m.link()
                    + "\n\nOr enter this code: " + m.formattedCode()
                    + "\n\nIf you didn't ask for this, ignore this email.");
            case EmailAddressChangedMessage m -> mailer.send(m.address(), "Your email address was changed",
                "Your account's email address is now " + m.maskedNewAddress()
                    + ". If you didn't do this, contact us right away.");
        }
    };
}
```

`EmailLinkMessage` is the sign-in email. It carries `address`, `link`, `code` and `expiresAt`. `formattedCode()` returns the code in groups, such as `K7F-2QM`, which is easier to read and type. The link is `link-base-url` followed by `#token=` and the token. The other two are for [adding or changing an address](#adding-or-changing-an-address): `EmailAddressConfirmationMessage` has the same fields plus `change`, and `EmailAddressChangedMessage` goes to the replaced address, with the new one in `newAddress` and `maskedNewAddress()`. Do not log a link or a code.

The default sender (through `JavaMailSender`) sends plain text: the link, the formatted code, and how long both stay valid. Its subjects are `who.email-link.subject`, `address-confirmation-subject` and `address-changed-subject`.

See the [Configuration Reference](configuration.md) for all eight properties.

## Code secret

`who.email-link.code-secret` is required. It must be at least 32 characters, and every instance of your application must use the same value. Who stores the code only as an HMAC keyed with this secret. The secret is never stored in the database, so a copy of the database does not let anyone work out or check codes offline. Keep it in your secret store, not in source control.

Generate it with `openssl rand -base64 32`, and do not reuse another secret, such as a JWT signing key or a database password. Like any secret property, it shows in `/actuator/env` and `/actuator/configprops` if those endpoints have `show-values` enabled.

If you change the secret, codes already sent stop working. The links in those emails still work.

## Wire the chain

With no `SecurityFilterChain` of your own there is nothing to wire: who's default chain mounts
email links, opens `/who/email/send` and `/who/email/sign-in` to signed-out requests, and does everything
listed below (see [Getting Started](getting-started.md#secure-the-app)). Open the page
`link-base-url` points at, and the assets it loads, yourself:

```yaml
who:
  security:
    public-paths:
      - /signin
      - /assets/**
```

### Your own chain

In a chain of your own, `WhoSecurity.all()` mounts every sign-in method at once (see [Getting
Started](getting-started.md#your-own-chain)). To mount the browser methods only, apply
`WhoWebSecurity.passwordless()` to the `SecurityFilterChain` that serves your browser app:

```java
@Bean
SecurityFilterChain browserChain(HttpSecurity http) throws Exception {
    http.with(WhoWebSecurity.passwordless(), Customizer.withDefaults());
    http.authorizeHttpRequests(a -> a
            .requestMatchers("/who/email/send", "/who/email/sign-in", "/signin").permitAll()
            .anyRequest().authenticated());
    return http.build();
}
```

`passwordless()` configures Spring's one-time-token login itself. Do not also call `oneTimeTokenLogin()` on the chain. Who's hooks are beans, so a second `oneTimeTokenLogin()` in the same app would pick them up too.

It also:

- turns on CSRF protection with Spring's `csrf.spa()`;
- hands unauthenticated requests to your application as a `NotSignedInException` (`401` unless you map it; see [Errors](errors.md)), with no redirect;
- makes `/who/logout` answer `204` and clear the `XSRF-TOKEN` cookie;
- changes the session id at sign-in;
- adds `Referrer-Policy: no-referrer`.

Startup fails if the chain disables CSRF or is `STATELESS`. A session in a chain without CSRF protection lets other sites forge requests. Do not combine `passwordless()` with a stateless chain. Who detects `sessionCreationPolicy(STATELESS)`, but not a stateless chain that also sets its own `SecurityContextRepository`. `WhoSecurity.all()` serves API keys and JWTs in the same chain, statelessly and without CSRF for the requests that carry them. With `passwordless()` alone, keep stateless `/api/**` callers in a separate chain:

```java
@Bean
@Order(1)
SecurityFilterChain apiChain(HttpSecurity http) throws Exception {
    http.securityMatcher("/api/**")
        .with(WhoApiKeySecurity.apiKey(), Customizer.withDefaults())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(AbstractHttpConfigurer::disable)
        .authorizeHttpRequests(a -> a.anyRequest().authenticated());
    return http.build();
}
```

The session holds only the identity id and the credential id. On every request who loads the identity and its permissions again. If the identity is no longer active, or the credential is no longer linked to it, the session is invalidated and the request gets `401`. Role and permission changes apply on the next request. To keep sessions in your database, see [Sessions](sessions.md).

A denied request is not saved: `passwordless()` uses a `NullRequestCache`, so there is no redirect to the original request after sign-in.

## The sign-in flow

The [TypeScript client](client.md#email-links) makes these calls for you, CSRF included: `who.email.send`, `who.email.signIn` and `who.email.signInFromLocation`.

All requests are JSON. Every POST needs the CSRF token. Spring sets an `XSRF-TOKEN` cookie that the page can read. The page sends its raw value in the `X-XSRF-TOKEN` header on every POST, including `/who/email/send`, `/who/email/sign-in` and `/who/logout`.

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
```

The cookie exists after the first response from the server, so make any GET first (for example `GET /who/me`, which answers `401` but sets the cookie). `/who/logout` clears the cookie. After signing out, make another GET before the next POST, or `/who/email/send` answers `403`.

1. **Ask for a link and code.** `POST /who/email/send` with `{"email": "user@example.com"}`. The answer is always `202` with an empty body, whether or not the address is registered. The server does the lookup and sends the email after it has answered.

    ```javascript
    await post('/who/email/send', { email });
    ```

    The email carries the link and a code. The code uses the Crockford base32 alphabet (`0-9` and `A-Z` without `I`, `L`, `O` and `U`), has `who.email-link.code-length` characters (default 6), and is shown in groups, such as `K7F-2QM`. The user can use either one.

2. **The user opens the link.** The browser loads your `/signin` page. The token is in the URL fragment, so it is never sent to a server. A mail scanner that fetches the link does nothing.

3. **Confirm.** The page reads the token from `location.hash` and posts it. Remove it from the address bar afterwards.

    ```javascript
    const token = new URLSearchParams(location.hash.slice(1)).get('token');
    history.replaceState(null, '', location.pathname);
    const res = await post('/who/email/sign-in', { token });
    if (res.ok) {
      const { identityId } = await res.json();
    }
    ```

    On success the answer is `200` with `{"identityId": "..."}` and a session cookie. Every failure is a `SignInFailedException` (`401` unless your application maps it otherwise; see [Errors](errors.md)): an unknown, used or expired token, a wrong, burnt or expired code, an identity that is not active, or a malformed body.

    **Or type the code.** A user who cannot open the link in this browser (the mail app opened it elsewhere, or a mail scanner used it up) types the code from the email instead. The page posts the address and the code to the same endpoint:

    ```javascript
    const res = await post('/who/email/sign-in', { email, code });
    ```

    The body is either `{"token": "..."}` or `{"email": "...", "code": "..."}`. A body with both, or with neither, fails. Using the link ends the code, and using the code ends the link. The code is not tied to the browser that asked for it, so a user can ask on a laptop and type the code on a phone.

4. **Use the session.** `GET /who/me` answers `{"identityId": "...", "permissions": ["..."]}` for a signed-in caller and throws `NotSignedInException` otherwise (`401`, in your application's error format).

5. **Sign out.** `POST /who/logout` answers `204` and clears the `XSRF-TOKEN` cookie. Fetch the cookie again with any GET (for example `GET /who/me`) before the next POST.

    ```javascript
    await post('/who/logout');
    await fetch('/who/me', { credentials: 'same-origin' }); // sets a new XSRF-TOKEN cookie
    ```

Each address holds one live link and code. A new request replaces both, so only the newest email works. A link or code works once.

### How the code is read

Input is case-insensitive. Spaces and dashes are ignored, so `k7f 2qm` and `K7F-2QM` are the same code. `O` is read as `0`, and `I` and `L` are read as `1`, so a mistyped look-alike still works.

### The attempt limit

A code is short, so it has an attempt limit. After `who.email-link.code-max-attempts` wrong codes (default 5) for an address, its code is burnt: even the right code fails. The link in the same email still works until it expires. A new request, once the [resend interval](#the-resend-interval) has passed, issues a fresh code with a fresh count.

A burnt code, a wrong code, an unknown address and an expired code all fail the same way, so the response tells an attacker nothing.

### How strong is the code

With the defaults, a code is one of 32^6 ≈ 1.07 billion values, and each code allows 5 guesses. An attacker can have a fresh code issued once per [resend interval](#the-resend-interval) (60 seconds by default), so about 7,200 guesses a day against one address. That is about a 1-in-150,000 chance per day of taking over one targeted address, while the victim receives about 1,440 emails.

Spraying many known addresses scales linearly: across 100,000 addresses, with no limit at the ingress, that is about 0.7 expected takeovers a day. So:

- per-IP rate limits at the ingress are required (see [Rate limiting at the ingress](#rate-limiting-at-the-ingress));
- for large user bases, set `who.email-link.code-length: 8` (about 1 trillion codes, 1024 times harder);
- alert on volumes of failed sign-ins.

Also, don't run `/who/email/sign-in` inside a transaction: the attempt count must commit even when the sign-in fails, or the attempt limit stops working. And limit the request body size at the ingress.

## Registering addresses

Only registered addresses can sign in. Register an address for an existing identity:

```java
Identity identity = whoService.createIdentity();
emailCredentialService.register(identity, "user@example.com");
```

Call `register` only with an address you vouch for, for example one you are sending an invitation to. Who does not verify ownership here. For a person adding an address to their own identity, use the [confirmed flow](#adding-or-changing-an-address) instead. `register` throws `EmailAddressTakenException` if the address is registered to another identity. If the address is already the identity's own, `register` returns that credential. If it is registered but linked to no identity (its identity was deleted directly, not with [`WhoService.deleteIdentity`](getting-started.md#deleting-an-identity)), `register` deletes that old credential, along with any sign-in link sent before, and registers a new one for the identity. Addresses are trimmed and lower-cased.

`EmailCredentialService` also has `findByAddress(String)` and `revoke(UUID credentialId)`. `revoke` deletes the credential and its link to the identity.

## Adding or changing an address

A signed-in person can add an address to their identity, or make an address theirs in place of one they have, by confirming a link or code sent to it. Until they confirm, nothing changes: an address is never attached without proof that the person reads its mailbox. Otherwise anyone could claim another person's address, which would then be taken, or get someone to sign in to an identity that is not theirs.

Both endpoints need a browser session for the signed-in identity, signed in within `who.security.recent-sign-in` (10 minutes by default), and a CSRF token like every POST. Signed out is `401` (`NotSignedInException`); an older sign-in is `401` (`ReauthenticationRequiredException`): sign the person in again and retry.

Ask for an address:

```js
await post('/who/email/addresses', { address: 'new@example.com' });            // 202
await post('/who/email/addresses', { address: 'new@example.com',
                                     replaces: '<email credential id>' });      // 202, a change
```

The answer is `202` whatever becomes of the request, and the rest runs on the email thread, as sign-in does, so the endpoint does not tell whether an address is taken. who sends the address a link and a code unless:

- the text is not an address;
- the address is registered already, to anyone, the caller included;
- the identity asked within `who.email-link.resend-interval`, for any address, or anyone asked for this address within it.

A new request replaces the identity's earlier one. Each is published as `EmailAddressConfirmationRequested` with its outcome. `replaces` must be one of the caller's own email credentials, or the answer is `404` (`EmailCredentialNotFoundException`).

The link is `who.email-link.address-confirmation-url` (default `link-base-url`) followed by `#confirm-address=` and the token. The page reads the token from the fragment and posts it, while signed in:

```js
await post('/who/email/addresses/confirm', { token });                          // { "credentialId": "..." }
await post('/who/email/addresses/confirm', { code: 'K7F-2QM' });                 // or the typed code
```

Only the identity that asked can confirm: the same token or code does nothing for anyone else signed in. A code counts against `who.email-link.code-max-attempts`, as at sign-in. A link or code works once and lasts `who.email-link.token-ttl`. No match is `400` (`InvalidEmailAddressConfirmationException`), and an address someone registered in the meantime is `409` (`EmailAddressTakenException`).

On confirmation who adds the address and publishes `CredentialAdded`. For a change, it adds the new address and revokes the old one in one transaction (`CredentialAdded`, then `CredentialRevoked`), and then emails the old address that it was replaced, showing the new one masked (`n***@example.com`). The old address is the one most likely to still belong to the real owner if someone else had their session, so it always hears about it. A failure to send that notice is logged and does not undo the change. Revoking the old address ends every session signed in with it, as any revoked credential does, including the one that made the change if it signed in with that address: the person signs in again with the new one.

To remove an address without replacing it, call `EmailCredentialService.revoke` from your own endpoint, after checking that the identity keeps another way to sign in.

`EmailAddressChanges` is the bean behind the endpoints, if you want the same flow under your own URLs.

## Sending a link from your own code

`POST /who/email/send` answers `202` at once and does the work in the background. To send a link yourself, for example in an invitation, call `EmailLinkIssuer.issue(address, tokenValue)`. The `EmailLinkIssuer` is a bean. `tokenValue` is a random, URL-safe value that you generate. Who generates the code itself.

`issue` returns an `EmailLinkOutcome`:

| Outcome | Meaning |
|---|---|
| `SENT` | The link and code were stored and handed to the `EmailLinkSender`. |
| `UNKNOWN_ADDRESS` | The text is not an address, or no email credential has it. |
| `INACTIVE_IDENTITY` | The address is known, but its identity is not active. |
| `RESEND_SUPPRESSED` | A link was sent within the [resend interval](#the-resend-interval). |
| `SEND_FAILED` | A step failed. The failure is logged, and a token already stored for that send is removed. |

`issue` never throws. The `/who/email/send` endpoint ignores the outcome, so it still reveals nothing about an address. Show the outcome only to a caller you trust, such as the owner who sent an invitation.

`issue` is synchronous and uses its own transactions. If you call it while a transaction of yours is open, call it after that transaction commits, with a new transaction of its own. Otherwise the send happens before your data is committed, and a failure in your transaction cannot take back the email:

```java
TransactionTemplate afterCommit = new TransactionTemplate(transactionManager);
afterCommit.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

TransactionSynchronizationManager.registerSynchronization(
    new TransactionSynchronization() {
        @Override
        public void afterCommit() {
            afterCommit.executeWithoutResult(
                status -> linkIssuer.issue(address, tokenValue));
        }
    });
```

The new transaction is needed because the finished transaction's connection is still bound while `afterCommit` runs, so the issuer's writes would not be committed.

## The resend interval

Who sends at most one email per address per `who.email-link.resend-interval` (default 60 seconds). A request inside that window still answers `202`, but no email goes out. This protects a person's inbox from email bombing. The worst an attacker can do to a victim is delay their sign-in email by one interval.

The link and the code expire together, after `who.email-link.token-ttl` (default 5 minutes). The resend interval must not be longer than that.

## Tracing the send

`/who/email/send` answers `202` at once and sends the email on a virtual thread from the
`whoEmailLinkExecutor` bean. That executor applies your `TaskDecorator` beans, as Spring Boot does
to its own executors, so the send can stay in the request's trace and logging context. With
Micrometer Tracing, register Spring's decorator:

```java
@Bean
TaskDecorator contextPropagatingTaskDecorator() {
  return new ContextPropagatingTaskDecorator();
}
```

To use your own executor instead, define a bean named `whoEmailLinkExecutor`.

## Rate limiting at the ingress

The attempt limit is per address, not per client. Who does not limit requests by client IP and never reads `X-Forwarded-For`. Put per-IP limits on `/who/email/send` and `/who/email/sign-in` at the load balancer, WAF or ingress, where the real client address is known. With [Passkeys](passkeys.md) on, limit `/who/passkey/sign-in/options` and `/who/passkey/sign-in` the same way: the options endpoint creates a session for any anonymous caller. For example, with nginx:

```nginx
limit_req_zone $binary_remote_addr zone=who_signin:10m rate=10r/m;

server {
    location = /who/email/send {
        limit_req zone=who_signin burst=5 nodelay;
        limit_req_status 429;
        proxy_pass http://app;
    }

    location = /who/email/sign-in {
        limit_req zone=who_signin burst=5 nodelay;
        limit_req_status 429;
        proxy_pass http://app;
    }
}
```

Tune the rate to your users. Behind another proxy, make sure `$binary_remote_addr` is the client address (use the `realip` module).

## Session cookie

Set the session cookie properties yourself. Who does not override them:

```yaml
server:
  servlet:
    session:
      cookie:
        same-site: lax
        secure: true
```

`HttpOnly` is the Spring Boot default. Use `secure: false` only for local development over plain HTTP.

## Trust and recovery

An email link signs in, so control of the mailbox is control of the identity. The mailbox is the root of trust.

A user who loses the mailbox needs an administrator. Call `EmailCredentialService.revoke` for the old credential, then `register` with a new, verified address. Who has no admin tooling for this.

## One tenant per JVM

Who assumes one tenant per JVM. Each customer application should have its own database. If one JVM serves several tenant databases, the token store and `WhoService` would need to be tenant-scoped. Who does not support that.

## Database

The module adds `who_email_credential`, `who_email_link_token` and `who_email_address_request` (a pending address change, one per identity). Token and request rows hold the hash of the token and the HMAC of the code, never the values. See [Database Setup](database.md).
