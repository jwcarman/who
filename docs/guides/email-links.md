# Email Links

Who supports sign-in by an emailed link, in the `who-email-link` module. The user enters an address, gets a link, and opens it. The link signs them in and starts an HTTP session. It is built on Spring Security's one-time-token login. Who supplies the hooks and keeps Spring's endpoints: `POST /ott/generate`, `POST /login/ott` and `POST /logout`. Who adds one endpoint of its own, `GET /who/me`.

Email links are opt-in. An application that uses only JWT or API keys configures nothing.

## Turn it on

Set the page of your app that receives sign-in links:

```yaml
who:
  email-link:
    link-base-url: https://app.example.com/signin
```

When this property is set, the application also needs a way to send mail. Either register an `EmailLinkSender` bean, or set `spring.mail.host` and `who.email-link.from` to send through Spring Boot's `JavaMailSender`:

```yaml
spring:
  mail:
    host: smtp.example.com
who:
  email-link:
    link-base-url: https://app.example.com/signin
    from: no-reply@example.com
```

Startup fails, and the message names the fix, if:

- `link-base-url` is blank;
- there is no `EmailLinkSender` and no `JavaMailSender` with `who.email-link.from`;
- `who.email-link.resend-interval` is longer than `who.email-link.token-ttl`.

A custom sender is one method:

```java
@Bean
EmailLinkSender emailLinkSender(MyMailer mailer) {
    return (address, link) -> mailer.send(address, "Your sign-in link", link);
}
```

The link is `link-base-url` followed by `#token=` and the token. Do not log it.

See the [Configuration Reference](configuration.md) for all five properties.

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
- answers a plain `401`, with no body and no redirect, to unauthenticated requests;
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

The session holds only the identity id and the credential id. On every request who loads the identity and its permissions again. If the identity is no longer active, or the credential is no longer linked to it, the session is invalidated and the request gets `401`. Role and permission changes apply on the next request.

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

The cookie exists after the first response from the server, so make any GET first (for example `GET /who/me`, which answers `401` but sets the cookie).

1. **Ask for a link.** `POST /ott/generate` with `{"email": "user@example.com"}`. The answer is always `202` with an empty body, whether or not the address is registered. The server does the lookup and sends the email after it has answered.

    ```javascript
    await post('/ott/generate', { email });
    ```

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

    On success the answer is `200` with `{"identityId": "..."}` and a session cookie. Every failure is `401` with `{"error":"invalid_token"}`: an unknown, used or expired token, an identity that is not active, or a malformed body.

4. **Use the session.** `GET /who/me` answers `{"identityId": "...", "permissions": ["..."]}` for a signed-in caller and `401` otherwise.

5. **Sign out.** `POST /logout` answers `204` and clears the `XSRF-TOKEN` cookie.

Each address holds one live link. A new link replaces the old one, so only the newest works. A link works once.

## Registering addresses

Only registered addresses can sign in. Register an address for an existing identity:

```java
Identity identity = whoService.createIdentity();
emailCredentialService.register(identity, "user@example.com");
```

Call `register` only with an address you have verified, for example one that received an invitation. Who does not verify ownership here. `register` throws `EmailAddressTakenException` if the address is registered. Addresses are trimmed and lower-cased.

`EmailCredentialService` also has `findByAddress(String)` and `revoke(UUID credentialId)`. `revoke` deletes the credential and its link to the identity.

## The resend interval

Who sends at most one email per address per `who.email-link.resend-interval` (default 60 seconds). A request inside that window still answers `202`, but no email goes out. This protects a person's inbox from email bombing. The worst an attacker can do to a victim is delay their sign-in email by one interval.

The link expires after `who.email-link.token-ttl` (default 5 minutes). The resend interval must not be longer than that.

## Rate limiting at the ingress

Who does not limit requests by client IP and never reads `X-Forwarded-For`. Put per-IP limits on `/ott/generate` and `/login/ott` at the load balancer, WAF or ingress, where the real client address is known. For example, with nginx:

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

The module adds `who_email_credential` and `who_email_link_token`. See [Database Setup](database.md).
