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

See [Email Links](email-links.md) for how these fit together.
