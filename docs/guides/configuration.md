# Configuration Reference

| Property | Default | Description |
|----------|---------|-------------|
| `who.initialize-schema` | `embedded` | When to run Who's bundled DDL scripts: `always`, `embedded` (H2/HSQLDB/Derby only), or `never` |
| `who.security.public-paths` | none | Paths of your app that who's default chain opens to signed-out requests, such as `/signin` and `/assets/**`. `/error`, the sign-in methods' endpoints and Actuator's health endpoint are open already. Also in `WhoPublicPaths` for a chain of your own |
| `who.email-link.enabled` | `true` | Turns email-link sign-in off when `false`, with `who-email-link` still on the classpath |
| `who.passkey.enabled` | `true` | Turns passkeys off when `false`, with `who-passkey` still on the classpath |
| `who.api-key.enabled` | `true` | Turns API keys off when `false`, with `who-apikey` still on the classpath |
| `who.jwt.enabled` | `true` | Turns JWT sign-in off when `false`, with `who-jwt` still on the classpath. When Spring Boot builds the decoder, `spring.security.oauth2.resourceserver.jwt.audiences` is required |
| `who.oidc.enabled` | `true` | Turns provider sign-in off when `false`, with `who-oidc` still on the classpath |
| `who.enrollment.enabled` | `true` | Turns enrollment tokens off when `false`, with `who-enrollment` still on the classpath |
| `who.enrollment.token-expiration` | `24h` | How long a newly issued enrollment token is valid (ISO-8601 duration, e.g. `PT12H`) |
| `who.security.last-sign-in-cookie.enabled` | `true` | Sets the `who_last_sign_in` cookie after a browser sign-in. See [Sessions](sessions.md#the-last-sign-in-cookie) |
| `who.method-security.enabled` | `true` | Enables method security so `@RequiresPermission` is enforced. Set to `false` to turn it off |
| `who.api-key.header-name` | `X-API-Key` | HTTP header used to pass API keys |
| `who.email-link.link-base-url` | none | Page of your app that receives sign-in links. The token is appended as `#token=...`. Required with `who-email-link`: a missing or blank value fails startup. List the page in `who.security.public-paths` |
| `who.email-link.token-ttl` | `5m` | How long a sign-in link stays valid |
| `who.email-link.resend-interval` | `60s` | Minimum time between two links for the same address. Startup fails if it is longer than `token-ttl` |
| `who.email-link.from` | none | Sender address of sign-in emails. Required when links go out through Spring Boot's `JavaMailSender` |
| `who.email-link.subject` | `Your sign-in link and code` | Subject of sign-in emails |
| `who.email-link.code-length` | `6` | Number of characters in the typed sign-in code. At least 6 |
| `who.email-link.code-max-attempts` | `5` | Wrong guesses a sign-in code survives before it is burnt. The link still works. At least 1 |
| `who.email-link.code-secret` | none | Secret that keys the code hash. Required with `who-email-link`, at least 32 characters, the same on every instance. Never stored in the database |
| `who.passkey.rp-id` | none | The application's own host, such as `app.example.com`. Required with `who-passkey`: startup fails without it. Never use a registrable domain that other apps share |
| `who.passkey.rp-name` | `who` | Name of the relying party, shown by authenticators. Must not be blank |
| `who.passkey.allowed-origins` | `https://<rp-id>` | Origins a registration or sign-in response may come from: each a scheme (`http` or `https`), a host and an optional port, with no path, such as `http://localhost:8080`. Startup fails on anything else |
| `who.passkey.registration-window` | `10m` | How recently the session must have signed in to register a passkey |
| `who.passkey.from` | none | Sender address of the "passkey added" email. The email is sent only when this is set, `who-email-link` is on and a `JavaMailSender` exists. It does not fall back to `who.email-link.from`. When the email cannot be sent, who logs a warning at startup |
| `who.oidc.success-url` | `/` | Where a provider sign-in lands. A path on this site |
| `who.oidc.failure-url` | `/` | Where every other provider sign-in outcome lands, with `error=no-account`, `already-linked`, `signed-in` or `signin-failed`. A path on this site; list it in `who.security.public-paths` |
| `who.oidc.sign-up-window` | `10m` | How long a pending sign-up waits for the person to confirm it |
| `who.oidc.link-window` | `10m` | How recently the identity must have signed in to link a provider account, and how long a started link lasts |
| `who.oidc.providers.<id>.enabled` | `true` | Leaves one client registration out of sign-in when `false` |
| `who.oidc.providers.<id>.issuer` | none | The issuer, for an OpenID Connect provider configured by endpoints, or an OAuth 2 provider |
| `who.oidc.providers.<id>.subject-attribute` | none | The user-info attribute holding the account's immutable id, for an OAuth 2 provider other than github.com |
| `who.oidc.providers.<id>.trust-email` | `true` for Google and github.com, else `false` | Whether a verified address from this provider reaches the sign-up policy |
| `who.oidc.providers.<id>.fresh-login` | `true` for OpenID Connect providers but Google, else `false` | Whether a link demands a fresh login at the provider (`max_age=0` and a recent `auth_time`) |

See [Email Links](email-links.md), [Passkeys](passkeys.md) and [Social Login](social-login.md) for how these fit together.
