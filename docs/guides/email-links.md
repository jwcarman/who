# Email Links

Who supports sign-in by an emailed link or code, in the `who-email-link` module. The user enters an address and gets an email with a link and a short code. Opening the link, or typing the code, signs them in and starts an HTTP session. It is built on Spring Security's one-time-token login. Who supplies the hooks and keeps Spring's endpoints: `POST /ott/generate`, `POST /login/ott` and `POST /logout`. Who adds one endpoint of its own, `GET /who/me`.

Email links are opt-in. An application that uses only JWT or API keys configures nothing.

## Turn it on

Set the page of your app that receives sign-in links:

```yaml
who:
  email-link:
    link-base-url: https://app.example.com/signin
    code-secret: <at least 32 random characters>
```

When this property is set, the application also needs a way to send mail. Either register an `EmailLinkSender` bean, or set `spring.mail.host` and `who.email-link.from` to send through Spring Boot's `JavaMailSender`:

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

- `link-base-url` is blank;
- `who.email-link.code-secret` is missing or shorter than 32 characters;
- there is no `EmailLinkSender` and no `JavaMailSender` with `who.email-link.from`;
- `who.email-link.resend-interval` is longer than `who.email-link.token-ttl`.

A custom sender is one method:

```java
@Bean
EmailLinkSender emailLinkSender(MyMailer mailer) {
    return message -> mailer.send(
        message.address(),
        "Your sign-in link and code",
        "Sign in: " + message.link()
            + "\n\nOr enter this code: " + message.formattedCode());
}
```

`EmailLinkMessage` carries `address`, `link`, `code` and `expiresAt`. `formattedCode()` returns the code in groups, such as `K7F-2QM`, which is easier to read and type. The link is `link-base-url` followed by `#token=` and the token. Do not log the link or the code.

The default sender (through `JavaMailSender`) sends plain text: the link, the formatted code, and how long both stay valid.

See the [Configuration Reference](configuration.md) for all eight properties.

## Code secret

`who.email-link.code-secret` is required. It must be at least 32 characters, and every instance of your application must use the same value. Who stores the code only as an HMAC keyed with this secret. The secret is never stored in the database, so a copy of the database does not let anyone work out or check codes offline. Keep it in your secret store, not in source control.

If you change the secret, codes already sent stop working. The links in those emails still work.

## Wire the chain

Apply `WhoWebSecurity.passwordless()` to the `SecurityFilterChain` that serves your browser app:

```java
@Bean
SecurityFilterChain browserChain(HttpSecurity http) throws Exception {
    http.with(WhoWebSecurity.passwordless(), Customizer.withDefaults());
    http.authorizeHttpRequests(a -> a
            .requestMatchers("/ott/generate", "/login/ott", "/signin").permitAll()
            .anyRequest().authenticated());
    return http.build();
}
```

`passwordless()` configures Spring's one-time-token login itself. Do not also call `oneTimeTokenLogin()` on the chain. Who's hooks are beans, so a second `oneTimeTokenLogin()` in the same app would pick them up too.

It also:

- turns on CSRF protection with Spring's `csrf.spa()`;
- hands unauthenticated requests to your application as a `NotSignedInException` (`401` unless you map it; see [Errors](errors.md)), with no redirect;
- makes `/logout` answer `204` and clear the `XSRF-TOKEN` cookie;
- changes the session id at sign-in;
- adds `Referrer-Policy: no-referrer`.

Startup fails if the chain disables CSRF or is `STATELESS`. A session in a chain without CSRF protection lets other sites forge requests. Do not combine `passwordless()` with a stateless chain. Who detects `sessionCreationPolicy(STATELESS)`, but not a stateless chain that also sets its own `SecurityContextRepository`. Keep stateless `/api/**` callers (JWT, API keys) in a separate chain:

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

All requests are JSON. Every POST needs the CSRF token. Spring sets an `XSRF-TOKEN` cookie that the page can read. The page sends its raw value in the `X-XSRF-TOKEN` header on every POST, including `/ott/generate`, `/login/ott` and `/logout`.

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

The cookie exists after the first response from the server, so make any GET first (for example `GET /who/me`, which answers `401` but sets the cookie). `/logout` clears the cookie. After signing out, make another GET before the next POST, or `/ott/generate` answers `403`.

1. **Ask for a link and code.** `POST /ott/generate` with `{"email": "user@example.com"}`. The answer is always `202` with an empty body, whether or not the address is registered. The server does the lookup and sends the email after it has answered.

    ```javascript
    await post('/ott/generate', { email });
    ```

    The email carries the link and a code. The code uses the Crockford base32 alphabet (`0-9` and `A-Z` without `I`, `L`, `O` and `U`), has `who.email-link.code-length` characters (default 6), and is shown in groups, such as `K7F-2QM`. The user can use either one.

2. **The user opens the link.** The browser loads your `/signin` page. The token is in the URL fragment, so it is never sent to a server. A mail scanner that fetches the link does nothing.

3. **Confirm.** The page reads the token from `location.hash` and posts it. Remove it from the address bar afterwards.

    ```javascript
    const token = new URLSearchParams(location.hash.slice(1)).get('token');
    history.replaceState(null, '', location.pathname);
    const res = await post('/login/ott', { token });
    if (res.ok) {
      const { identityId } = await res.json();
    }
    ```

    On success the answer is `200` with `{"identityId": "..."}` and a session cookie. Every failure is a `SignInFailedException` (`401` unless your application maps it otherwise; see [Errors](errors.md)): an unknown, used or expired token, a wrong, burnt or expired code, an identity that is not active, or a malformed body.

    **Or type the code.** A user who cannot open the link in this browser (the mail app opened it elsewhere, or a mail scanner used it up) types the code from the email instead. The page posts the address and the code to the same endpoint:

    ```javascript
    const res = await post('/login/ott', { email, code });
    ```

    The body is either `{"token": "..."}` or `{"email": "...", "code": "..."}`. A body with both, or with neither, fails. Using the link ends the code, and using the code ends the link. The code is not tied to the browser that asked for it, so a user can ask on a laptop and type the code on a phone.

4. **Use the session.** `GET /who/me` answers `{"identityId": "...", "permissions": ["..."]}` for a signed-in caller and throws `NotSignedInException` otherwise (`401`, in your application's error format).

5. **Sign out.** `POST /logout` answers `204` and clears the `XSRF-TOKEN` cookie. Fetch the cookie again with any GET (for example `GET /who/me`) before the next POST.

    ```javascript
    await post('/logout');
    await fetch('/who/me', { credentials: 'same-origin' }); // sets a new XSRF-TOKEN cookie
    ```

Each address holds one live link and code. A new request replaces both, so only the newest email works. A link or code works once.

### How the code is read

Input is case-insensitive. Spaces and dashes are ignored, so `k7f 2qm` and `K7F-2QM` are the same code. `O` is read as `0`, and `I` and `L` are read as `1`, so a mistyped look-alike still works.

### The attempt limit

A code is short, so it has an attempt limit. After `who.email-link.code-max-attempts` wrong codes (default 5) for an address, its code is burnt: even the right code fails. The link in the same email still works until it expires. A new request, once the [resend interval](#the-resend-interval) has passed, issues a fresh code with a fresh count.

A burnt code, a wrong code, an unknown address and an expired code all fail the same way, so the response tells an attacker nothing.

## Registering addresses

Only registered addresses can sign in. Register an address for an existing identity:

```java
Identity identity = whoService.createIdentity();
emailCredentialService.register(identity, "user@example.com");
```

Call `register` only with an address you have verified, for example one that received an invitation. Who does not verify ownership here. `register` throws `EmailAddressTakenException` if the address is registered to another identity. If the address is already the identity's own, `register` returns that credential. If it is registered but linked to no identity (its identity was deleted directly, not with [`WhoService.deleteIdentity`](getting-started.md#deleting-an-identity)), `register` deletes that old credential, along with any sign-in link sent before, and registers a new one for the identity. Addresses are trimmed and lower-cased.

`EmailCredentialService` also has `findByAddress(String)` and `revoke(UUID credentialId)`. `revoke` deletes the credential and its link to the identity.

## Sending a link from your own code

`POST /ott/generate` answers `202` at once and does the work in the background. To send a link yourself, for example in an invitation, call `EmailLinkIssuer.issue(address, tokenValue)`. The `EmailLinkIssuer` is a bean. `tokenValue` is a random, URL-safe value that you generate. Who generates the code itself.

`issue` returns an `EmailLinkOutcome`:

| Outcome | Meaning |
|---|---|
| `SENT` | The link and code were stored and handed to the `EmailLinkSender`. |
| `UNKNOWN_ADDRESS` | The text is not an address, or no email credential has it. |
| `INACTIVE_IDENTITY` | The address is known, but its identity is not active. |
| `RESEND_SUPPRESSED` | A link was sent within the [resend interval](#the-resend-interval). |
| `SEND_FAILED` | A step failed. The failure is logged, and a token already stored for that send is removed. |

`issue` never throws. The `/ott/generate` endpoint ignores the outcome, so it still reveals nothing about an address. Show the outcome only to a caller you trust, such as the owner who sent an invitation.

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

## Rate limiting at the ingress

The attempt limit is per address, not per client. Who does not limit requests by client IP and never reads `X-Forwarded-For`. Put per-IP limits on `/ott/generate` and `/login/ott` at the load balancer, WAF or ingress, where the real client address is known. With [Passkeys](passkeys.md) on, limit `/webauthn/authenticate/options` and `/login/webauthn` the same way: the options endpoint creates a session for any anonymous caller. For example, with nginx:

```nginx
limit_req_zone $binary_remote_addr zone=who_signin:10m rate=10r/m;

server {
    location = /ott/generate {
        limit_req zone=who_signin burst=5 nodelay;
        limit_req_status 429;
        proxy_pass http://app;
    }

    location = /login/ott {
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

The module adds `who_email_credential` and `who_email_link_token`. The token row holds the hash of the token and the HMAC of the code, never the values. See [Database Setup](database.md).
