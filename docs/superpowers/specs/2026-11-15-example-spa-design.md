# Example: a single-page app on who

## Intent

The second of who's examples (see [the web-app design](2026-11-15-example-web-app-design.md)):
the app most people build today, a React front end over a Spring backend that answers JSON. It
shows that the guides' JavaScript works as written in that setting, and teaches the one catch
everyone hits: every URL who puts in an email, and every origin a passkey is checked against, is
the port the browser sees, not the port Spring listens on.

## Goals

- `./mvnw spring-boot:run -pl who-examples/spa` on a fresh clone, with Java and Docker, gives the
  app at `http://localhost:8080`: Maven fetches Node, builds the front end, and Spring serves it
  from the same origin.
- `npm run dev` in `who-examples/spa/frontend` gives hot reload at `http://localhost:5173`, with
  Vite forwarding who's and the app's paths to Spring on 8080, which runs with the `vite` profile.
- The same "Who Tasks" app as the web-app, so the two can be read side by side: the task board,
  members and invites, roles with `@RequiresPermission`, a profile with passkeys, linked provider
  accounts and adding or changing an email address; sign-in by email link or code, passkey, and
  Keycloak (Google and GitHub behind profiles), provider sign-up by policy, and confirmed links.
- `who.ts`: the guides' calls with no React in it; the components call it.

## Non-goals

- Browser tests, or front-end unit tests. Light testing, as the web-app.
- A polished UI or a component library. Plain components with a small stylesheet.
- Running both examples at once: they use the same ports (8080, 5432, 1025, 8025, 8280).

## Anti-goals

- Nothing unsafe: no CORS, no `SameSite=None`, no `csrf.disable()`, no token in local storage, no
  hand-written security chain. The front end and the API are one origin in both modes.
- No step outside `spring-boot:run` for the built mode: no global Node, no `npm install` first.
- who must not be hidden behind React: every call to who is in `who.ts`, readable on its own.

## Approaches

- **Front end served by Spring with no build step**: cheap, but barely different from the web-app.
- **Vite and React, same origin through Vite's dev server proxy (chosen by the owner)**: how such
  apps are built, and it surfaces the port catch.
- **Front end on its own origin with CORS**: needs `SameSite=None` cookies and CORS around the
  session, against who's cookie hardening. Ruled out.

## The design

### Layout

```
who-examples/spa/
  pom.xml                artifactId who-example-spa; frontend-maven-plugin builds frontend/
  compose.yaml           as the web-app's
  keycloak/realm.json    as the web-app's, redirect URIs for 8080 and 5173
  README.md
  frontend/              package.json (exact versions) and lockfile, vite.config.ts, src/
  src/main/java/...      org.jwcarman.who.examples.spa: JSON controllers, setup, sign-up policy
  src/main/resources/    application.yml, schema.sql
  src/test/java/...
```

The Vite build writes into `target/classes/static`, so the jar serves it. Any path that is not an
API, who or static path forwards to `index.html` for client-side routing.

### Backend

The web-app's domain code (tasks, members, roles, invitations, setup, sign-up policy) with its
Thymeleaf controllers replaced by `@RestController`s under `/api`: `GET /api/me` (identity,
permissions, email addresses, passkeys, linked accounts, enabled providers), tasks, members,
invites, unlink. who's default chain answers a signed-out `/api` call with `401`.

### The two modes

| Setting | Built (8080, default) | `vite` profile (5173) |
|---|---|---|
| `who.email-link.link-base-url` | `http://localhost:8080/signin/link` | `http://localhost:5173/signin/link` |
| `who.email-link.address-confirmation-url` | `http://localhost:8080/profile` | `http://localhost:5173/profile` |
| `who.passkey.allowed-origins` | `http://localhost:8080` | `http://localhost:5173` |
| provider sign-in, landings | relative paths; the provider returns to the port the browser came from, since Vite forwards `/oauth2` and `/login/oauth2` with the original host | same |

Vite forwards `/api`, `/who`, `/ott`, `/login`, `/logout`, `/webauthn` and `/oauth2` to 8080,
keeping the browser's host. The README explains the table.

### Front end

TypeScript, React, React Router. Pages: sign-in (link, code, passkey, providers,
`pending=sign-up`, the error landings), `/signin/link`, board, members, profile (passkeys,
`pending=link`, addresses and `#confirm-address=`). A `401` from `/api` sends the app to sign-in.

### Tests

- The backend starts with a Testcontainers Postgres and Docker Compose off.
- A signed-in member gets `GET /api/tasks`; a stranger gets `401`.
- `verify` builds the front end (the type check and Vite build fail the build).

### Build

The frontend-maven-plugin installs a pinned Node into `target`, runs `npm ci` and `npm run
build`. `-Dskip.npm` skips it, for an offline build of the rest of who. The publish and api-docs
workflows exclude the module, as they do the web-app.

## Left to the builder

Exact component structure, styling, the plugin's Node version (current LTS), and how Spring
forwards client routes to `index.html`.
