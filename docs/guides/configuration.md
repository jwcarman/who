# Configuration Reference

| Property | Default | Description |
|----------|---------|-------------|
| `who.base-path` | `/who` | The root of every who endpoint: `<root>/me`, `<root>/sessions`, `<root>/logout`, `<root>/email/...`, `<root>/passkey/...` and `<root>/oidc/...`, including the provider callback. A path that starts with `/`, does not end with `/`, is not `/` alone, and holds no `*{}?#;` or whitespace; anything else fails startup. See [Endpoints](#endpoints) |
| `who.initialize-schema` | `embedded` | When to run Who's bundled DDL scripts: `always`, `embedded` (H2/HSQLDB/Derby only), or `never` |
| `who.security.public-paths` | none | Paths of your app that who's default chain opens to signed-out requests, such as `/signin` and `/assets/**`. `/error`, the sign-in methods' endpoints and Actuator's health endpoint are open already. Also in `WhoPublicPaths` for a chain of your own |
| `who.email-link.enabled` | `true` | Turns email-link sign-in off when `false`, with `who-email-link` still on the classpath |
| `who.passkey.enabled` | `true` | Turns passkeys off when `false`, with `who-passkey` still on the classpath |
| `who.api-key.enabled` | `true` | Turns API keys off when `false`, with `who-apikey` still on the classpath |
| `who.jwt.enabled` | `true` | Turns JWT sign-in off when `false`, with `who-jwt` still on the classpath. When Spring Boot builds the decoder, `spring.security.oauth2.resourceserver.jwt.audiences` is required |
| `who.turnstile.enabled` | `true` | Turns the Turnstile human check off when `false`, with `who-turnstile` still on the classpath |
| `who.oidc.enabled` | `true` | Turns provider sign-in off when `false`, with `who-oidc` still on the classpath |
| `who.enrollment.enabled` | `true` | Turns enrollment tokens off when `false`, with `who-enrollment` still on the classpath |
| `who.enrollment.token-expiration` | `24h` | How long a newly issued enrollment token is valid (ISO-8601 duration, e.g. `PT12H`) |
| `who.security.recent-sign-in` | `10m` | How recently the caller must have signed in to add a way to sign in (register a passkey, link a provider account, add or change an email address), to [end another session](sessions.md#see-and-end-sessions), and to call an action marked [`@RequiresRecentSignIn`](security-model.md#recent-sign-in) without a window of its own. Checked when the request starts and when it is confirmed; a started provider link lasts as long |
| `who.security.last-sign-in-cookie.enabled` | `true` | Sets the `who_last_sign_in` cookie after a browser sign-in. See [Sessions](sessions.md#the-last-sign-in-cookie) |
| `who.sessions.auto-purge` | `true` | Each sign-in deletes one batch of ended or abandoned session records. Set to `false` to call `WhoSessions.purgeEndedSessions()` on a schedule of your own. See [Sessions](sessions.md#purging-old-rows) |
| `who.sessions.purge-batch-size` | `100` | The most session records one purge deletes. Positive, or startup fails |
| `who.sessions.last-seen-interval` | `5m` | How often, at most, a session's last use is written. Positive, or startup fails. How long a session lasts without a request is `spring.session.timeout` with Spring Session, else `server.servlet.session.timeout`, read as Spring Boot reads them (a bare number is seconds, an empty value is unset). Since a busy session's last use can lag by up to this interval, who treats a record as abandoned only after the timeout plus this interval |
| `who.method-security.enabled` | `true` | Enables method security so `@RequiresPermission` is enforced. Set to `false` to turn it off, for instance to configure `@EnableMethodSecurity` yourself. `@RequiresRecentSignIn` is enforced either way |
| `who.api-key.header-name` | `X-API-Key` | HTTP header used to pass API keys |
| `who.api-key.prefix` | `who_` | Starts every new API key, so secret scanners can spot one. Letters, digits and `_`, 2 to 20 characters, ending in `_`, or startup fails. Changing it leaves existing keys working |
| `who.api-key.max-lifetime` | `365d` | The longest lifetime a new API key may be given. Positive, or startup fails |
| `who.api-key.last-used-interval` | `5m` | How often, at most, an API key's last use is written. Positive, or startup fails |
| `who.email-link.link-base-url` | none | Page of your app that receives sign-in links. The token is appended as `#token=...`. Required with `who-email-link`: a missing or blank value fails startup. List the page in `who.security.public-paths` |
| `who.email-link.token-ttl` | `5m` | How long a sign-in link stays valid |
| `who.email-link.resend-interval` | `60s` | Minimum time between two links for the same address. Startup fails if it is longer than `token-ttl` |
| `who.email-link.from` | none | Sender address of sign-in emails. Required when links go out through Spring Boot's `JavaMailSender` |
| `who.email-link.subject` | `Your sign-in link and code` | Subject of sign-in emails |
| `who.email-link.address-confirmation-url` | `link-base-url` | Page of your app that receives the link confirming an address being added or changed. The token is appended as `#confirm-address=...`. The person must be signed in there |
| `who.email-link.address-confirmation-subject` | `Confirm your email address` | Subject of the email confirming an address being added or changed |
| `who.email-link.address-changed-subject` | `Your email address was changed` | Subject of the email telling an address it was replaced |
| `who.email-link.code-length` | `6` | Number of characters in the typed sign-in code. At least 6 |
| `who.email-link.code-max-attempts` | `5` | Wrong guesses a sign-in code survives before it is burnt. The link still works. At least 1 |
| `who.email-link.code-secret` | none | Secret that keys the code hash. Required with `who-email-link`, at least 32 characters, the same on every instance. Never stored in the database |
| `who.turnstile.secret-key` | none | The Turnstile widget's secret key. Required with `who-turnstile`: a missing, blank or unresolved value fails startup. See [Human check](email-links.md#human-check) |
| `who.turnstile.hostnames` | none | Hostnames a Turnstile token may be made on. Empty accepts any |
| `who.turnstile.timeout` | `3s` | How long to wait for Cloudflare, to connect and to answer |
| `who.turnstile.fail-open` | `false` | Lets requests through when Cloudflare cannot be reached or answers an error. A token Cloudflare refuses always fails |
| `who.passkey.rp-id` | none | The application's own host, such as `app.example.com`. Required with `who-passkey`: startup fails without it. Never use a registrable domain that other apps share |
| `who.passkey.rp-name` | `who` | Name of the relying party, shown by authenticators. Must not be blank |
| `who.passkey.allowed-origins` | `https://<rp-id>` | Origins a registration or sign-in response may come from: each a scheme (`http` or `https`), a host and an optional port, with no path, such as `http://localhost:8080`. Startup fails on anything else |
| `who.passkey.from` | none | Sender address of the "passkey added" email. The email is sent only when this is set, `who-email-link` is on and a `JavaMailSender` exists. It does not fall back to `who.email-link.from`. When the email cannot be sent, who logs a warning at startup |
| `who.oidc.success-url` | `/` | Where a provider sign-in lands. A path on this site |
| `who.oidc.failure-url` | `/` | Where a pending sign-up lands, with `pending=sign-up`, and every failed provider sign-in, with `error=already-linked`, `signed-in` or `signin-failed`. A path on this site; list it in `who.security.public-paths` |
| `who.oidc.link-url` | `success-url` | Where a link the signed-in person asked for lands: `pending=link` to confirm it, `error=already-linked`, or nothing for an account they already had. A path on this site, such as the account settings page |
| `who.oidc.sign-up-window` | `10m` | How long a pending sign-up waits for the person to confirm it |
| `who.oidc.providers.<id>.enabled` | `true` | Leaves one client registration out of sign-in when `false` |
| `who.oidc.providers.<id>.issuer` | none | The issuer, for an OpenID Connect provider configured by endpoints, or an OAuth 2 provider |
| `who.oidc.providers.<id>.subject-attribute` | none | The user-info attribute holding the account's immutable id, for an OAuth 2 provider other than github.com |
| `who.oidc.providers.<id>.trust-email` | `true` for Google and github.com, else `false` | Whether a verified address from this provider reaches the sign-up policy |

See [Email Links](email-links.md), [Passkeys](passkeys.md) and [Social Login](social-login.md) for how these fit together.

## Endpoints

Every who endpoint lives under `who.base-path`. With the default `/who`:

| Endpoint | Module | What it does |
|---|---|---|
| `GET /who/me` | who-spring-security | The signed-in identity and its permissions |
| `GET /who/sessions`, `DELETE /who/sessions/{id}`, `DELETE /who/sessions?others=true` | who-spring-security | See and end sessions |
| `POST /who/logout` | who-spring-security | Sign out |
| `POST /who/email/send` | who-email-link | Email a sign-in link and code |
| `POST /who/email/sign-in` | who-email-link | Sign in with the link's token, or the address and code |
| `POST /who/email/addresses`, `POST /who/email/addresses/confirm` | who-email-link | Add or change an email address |
| `POST /who/passkey/register/options`, `POST /who/passkey/register` | who-passkey | Register a passkey |
| `DELETE /who/passkey/register/{id}` | who-passkey | Remove one of your passkeys |
| `POST /who/passkey/sign-in/options`, `POST /who/passkey/sign-in` | who-passkey | Sign in with a passkey |
| `GET /who/oidc/authorize/{registrationId}` | who-oidc | Start a provider sign-in |
| `GET /who/oidc/callback/{registrationId}` | who-oidc | The provider's redirect URI |
| `GET /who/oidc/pending`, `POST /who/oidc/sign-up`, `POST /who/oidc/link` | who-oidc | Confirm a pending sign-up or link |
| `POST /who/oidc/link/{registrationId}` | who-oidc | Start linking a provider account |

Your own endpoints may live under the same root; who's rules apply only to its own paths, so
yours still need whatever sign-in your chain asks for.
