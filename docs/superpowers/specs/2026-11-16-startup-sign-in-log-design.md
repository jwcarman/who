# Startup log of the sign-in methods each chain accepts

## Intent

Each who module turns on with its jar and off with `who.<module>.enabled=false`, so which sign-in
methods an application accepts can differ from what its author expects: a missing jar, a stray
`enabled=false`. One INFO line per chain at startup names them, where the author already looks.

## Goals

- When who builds a security chain, it logs at INFO the sign-in methods that chain accepts.
- Browser and stateless methods are named separately, since they authenticate differently.
- Provider sign-in names its registrations (`google`, `github`): registration ids, not secrets, so
  they are fine at INFO under who's logging rule.
- An application's own chain built with who's configurers logs the same way.

## Non-goals and anti-goals

- No Actuator `info` contributor. Dropped: a running application would tell anyone who can read
  `/actuator/info` how it can be attacked. The roadmap item is removed.
- No secrets, keys, client ids or issuer URLs in the line.
- No change to what happens when a chain has no methods: the browser and `all()` chains still warn,
  the stateless chain still fails startup.

## Approaches

1. **Actuator `info` contributor.** Visible on a running app without its logs. Rejected for the
   reason above.
2. **One listener at startup listing every method bean.** Simple, but it reports beans, not chains:
   an application with its own chains gets a line that says nothing about what each accepts.
3. **Each configurer logs as it adds the methods to its chain** (chosen). The line describes what the
   chain really does, and every chain, who's default or the application's, reports itself.

## Design

- `PasswordlessLoginMethod` and `StatelessLoginMethod` gain
  `default String name()`, returning the class's simple name, so a custom method (or a lambda for
  the functional `PasswordlessLoginMethod`) still compiles. who's own methods return `email-link`,
  `passkey`, `oidc`, `api-key` and `jwt`.
- `OidcLoginMethod.name()` returns `oidc (<registration ids, sorted, comma-separated>)`, read from
  the `SignInRegistrations` it serves; plain `oidc` with none.
- `PasswordlessConfigurer` logs, after configuring its methods:
  `who: this chain signs browsers in with email-link, passkey, oidc (github, google) under /who`
  (the base path from `WhoPaths`).
- `WhoStatelessConfigurer` logs: `who: this chain accepts api-key, jwt`.
- `WhoSecurityConfigurer` (`WhoSecurity.all()`) logs its stateless methods the same way; its
  browser methods are logged by the `PasswordlessConfigurer` it applies, so nothing is logged twice.
- Docs: the observability guide's logging section names the line; CHANGELOG Added entry; ROADMAP
  drops the Actuator item and records why.

## Left to the builder

- Exact wording and punctuation of the two lines, within the shape above.
- Test style: capture the log with the project's existing approach to log assertions.
