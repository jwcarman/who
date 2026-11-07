# Configuration Reference

| Property | Default | Description |
|----------|---------|-------------|
| `who.initialize-schema` | `embedded` | When to run Who's bundled DDL scripts: `always`, `embedded` (H2/HSQLDB/Derby only), or `never` |
| `who.enrollment.token-expiration` | `24h` | How long a newly issued enrollment token is valid (ISO-8601 duration, e.g. `PT12H`) |
| `who.method-security.enabled` | `true` | Enables method security so `@RequiresPermission` is enforced. Set to `false` to turn it off |
| `who.api-key.header-name` | `X-API-Key` | HTTP header used to pass API keys |
| `who.email-link.link-base-url` | none | Page of your app that receives sign-in links. The token is appended as `#token=...`. Setting it turns email-link sign-in on. When it is unset, no email-link beans exist. A blank value fails startup |
| `who.email-link.token-ttl` | `5m` | How long a sign-in link stays valid |
| `who.email-link.resend-interval` | `60s` | Minimum time between two links for the same address. Startup fails if it is longer than `token-ttl` |
| `who.email-link.from` | none | Sender address of sign-in emails. Required when links go out through Spring Boot's `JavaMailSender` |
| `who.email-link.subject` | `Your sign-in link` | Subject of sign-in emails |
| `who.passkey.rp-id` | none | The application's own host, such as `app.example.com`. Setting it turns passkeys on. When it is unset, no passkey beans exist. Never use a registrable domain that other apps share |
| `who.passkey.rp-name` | `who` | Name of the relying party, shown by authenticators. Must not be blank |
| `who.passkey.allowed-origins` | `https://<rp-id>` | Origins a registration or sign-in response may come from: each a scheme (`http` or `https`), a host and an optional port, with no path, such as `http://localhost:8080`. Startup fails on anything else |
| `who.passkey.registration-window` | `10m` | How recently the session must have signed in to register a passkey |
| `who.passkey.from` | none | Sender address of the "passkey added" email. The email is sent only when this is set, `who-email-link` is on and a `JavaMailSender` exists. It does not fall back to `who.email-link.from`. When the email cannot be sent, who logs a warning at startup |

See [Email Links](email-links.md) and [Passkeys](passkeys.md) for how these fit together.
