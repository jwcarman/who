# Configuration Reference

| Property | Default | Description |
|----------|---------|-------------|
| `who.initialize-schema` | `embedded` | When to run Who's bundled DDL scripts: `always`, `embedded` (H2/HSQLDB/Derby only), or `never` |
| `who.enrollment.token-expiration` | `24h` | How long a newly issued enrollment token is valid (ISO-8601 duration, e.g. `PT12H`) |
| `who.method-security.enabled` | `true` | Enables method security so `@RequiresPermission` is enforced. Set to `false` to turn it off |
| `who.api-key.header-name` | `X-API-Key` | HTTP header used to pass API keys |
