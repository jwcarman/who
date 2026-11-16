# Example: who's observability in the Grafana LGTM stack

## Intent

who counts sign-ins, failures and sign-in emails as Micrometer meters, times its own work as
observations, and logs why each sign-in failed (see the Observability guide). Nothing shows it
working. This example does: one command, and Grafana at `localhost:3000` shows who's dashboard
filling up, with every failure reason, and a person can click an action and find their own
request's trace and log lines.

The owner wants it targeted at observability, not a sign-in demo with observability bolted on.
That is why it is a new example rather than the web-app example with Grafana added.

## Goals

- `./mvnw spring-boot:run -pl who-examples/observability` on a fresh clone, with Docker running,
  starts Postgres, Mailpit and `grafana/otel-lgtm`, and nothing else is set up by hand.
- Within a minute, a provisioned "who" dashboard in Grafana shows data in every panel.
- A page of actions lets a person cause each kind of sign-in and failure themselves, and after
  each one shows the request's trace id with a link that opens that trace in Grafana.
- Metrics, traces and logs are connected: a failed sign-in's log line carries the trace id; the
  trace shows who's `who.principal.resolve` span inside Spring's request span.
- who needs no observability configuration of its own: the example adds only Spring Boot's
  OpenTelemetry starter and Actuator.

## Non-goals

- JWT sign-in. It needs Keycloak; the README says the `jwt` method appears the same way.
- Passkeys and provider sign-in (no browser ceremony to automate, no IdP).
- A production observability setup (retention, auth on Grafana, sampling policy).
- Thorough tests of the example; who's own tests cover the meters.

## Anti-goals

- No personal data in any tag, span attribute name or dashboard query. The README points out
  that who's tags hold only enum values.
- Nothing the example teaches is unsafe: CSRF stays on for the page, keys hold only scopes.
- No step beyond `spring-boot:run`.

## Approaches

1. **A new small app built for observability, with synthetic traffic and a page of actions
   (chosen).** Charts fill on their own; a person's own click is findable by its trace id.
2. **The web-app example with LGTM added.** Real sign-in traffic, no new app, but not targeted at
   observability, and no API keys or JWTs, so several failure reasons never appear.
3. **A new app with synthetic traffic only.** Charts fill, but nobody sees their own action light
   up, which is what makes it click.

The owner picked a targeted example; whether a person would want to click through themselves led
to combining traffic and a page.

## The app: `who-examples/observability`

- Modules: `who-spring-boot-starter`, `who-email-link`, `who-apikey`; Spring Boot webmvc, JDBC,
  Postgres, mail, Actuator, `spring-boot-starter-opentelemetry`, Docker Compose.
- who's default chain (no `SecurityFilterChain` in the app): email links and codes for the page,
  API keys for `/api/**`.
- `Setup` creates one person, `demo@example.com`, with a role granting `greeting.read` and
  `greeting.write`.
- A tiny API: `GET /api/greeting` needs `greeting.read`; `POST /api/greeting` needs
  `greeting.write`.
- The browser's keys: `POST /keys` (signed in, CSRF-protected) makes a key with `greeting.read`
  only, so a write with it is `403`; `DELETE /keys/{id}` revokes it.

### Observability wiring

- `compose.yaml` adds `grafana/otel-lgtm` (Grafana on 3000); Spring Boot's Docker Compose support
  connects OTLP metrics, traces and logs to it.
- Tracing samples every request (`management.tracing.sampling.probability: 1.0`) in the example.
- Logs reach Loki through the OpenTelemetry Logback appender, installed as Spring Boot documents.
- who's DEBUG failure reasons are on (`logging.level.org.jwcarman.who: DEBUG`), so a failed
  sign-in has a log line with its trace id.
- Every response carries an `X-Trace-Id` header with the current trace id, so the page and the
  generator can show it.

### The dashboard

Provisioned into the LGTM container's Grafana from a JSON file in the example. Panels:

- Sign-ins per second by method and outcome (`who.sign-ins`).
- Failed sign-ins by reason.
- Sign-in emails by outcome (`who.sign-in.emails`).
- Credentials added and revoked by kind; sessions ended by reason; sign-outs.
- Spring Security's authentication timings and HTTP latency for `/api/**` and the sign-in paths.
- Recent who log lines from Loki (failures with their reasons).

### The page (`/`)

Plain HTML and a little JavaScript, in the web-app example's style. One button per action, and a
"your last request" box showing status, what happened, and the trace id with a link to Grafana's
Explore view of that trace:

- Email me a code (to `demo@example.com`; Mailpit shows it), Sign in with the code, Sign in with a
  wrong code, Email a code to an unknown address.
- Make a key, Call with it, Write with it (403: missing scope), Call with an unknown key, Revoke
  the key, Call with the revoked key.
- Sign out.

### The traffic generator

On by default; `example.traffic.enabled=false` turns it off. Every few seconds it picks a scenario
and makes a real HTTP request to the app (so traces are real requests), marked with the header
`X-Traffic: synthetic`:

- API calls with a good key, an unknown key, a revoked key, an expired key, and a key without the
  scope; keys it needs are made and revoked through `ApiKeyService`.
- Code requests for the known address and an unknown one; sign-ins with a wrong code; and a
  correct code read back from Mailpit's API, so successful email sign-ins appear too.

HTTP server metrics and request spans carry a `traffic` key (`synthetic` or `interactive`) from
that header, so the dashboard can show either. who's own counters cannot be split this way: they
are tagged only with who's enums, and the README says so.

### Tests

- The app starts with Testcontainers Postgres, Docker Compose and the generator off, and OTLP
  export off.
- A response carries `X-Trace-Id`.
- A key made through `/keys` reads the greeting and is `403` writing it; an unknown key is `401`.

### README

Run it; open Grafana and the dashboard; open the page and try actions; follow a trace id to the
trace and to its log lines; what each panel shows and which meter it reads; that who needed no
configuration; turning the generator off; in production (sampling, retention, protecting the
metrics, no Grafana defaults).

### Elsewhere

`who-examples/pom.xml` module, `maven-publish.yml` exclude, `docs/index.md` examples list, the
README's examples paragraph, the Observability guide links to the example, CHANGELOG.

## Left to the builder

- The scenario mix and interval, the exact Grafana links and dashboard layout.
- How the generator handles the resend interval and code attempts.
- Reusing the web-app example's stylesheet and CSRF helper.
