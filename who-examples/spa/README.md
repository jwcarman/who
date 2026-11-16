# Example: a single-page app

The same team task board as the [web app example](../web-app), built the way most apps are today:
a React front end (TypeScript, Vite, React Router) over a Spring backend that answers JSON. People
sign in with an email link or a typed code, a passkey, or an identity provider. The backend has no
`SecurityFilterChain` of its own: who's default chain does all of it, with sessions and Spring's
`csrf.spa()`. Nothing about the session is kept in the browser beyond its cookies.

The who calls go through [`@jwcarman/who-client`](../../who-client), created once in
[`who-client.ts`](frontend/src/who-client.ts): it handles CSRF, the passkey encoding and the
fragment tokens, and each call answers a typed result the pages switch on.

## Run it

You need Java 25 and Docker; not Node, which the build downloads. From the repository root:

```bash
./mvnw install -DskipTests
./mvnw spring-boot:run -pl who-examples/spa
```

Maven builds the front end into the jar, and Spring serves it and the API from one origin,
<http://localhost:8080>. Spring Boot's Docker Compose support starts Postgres, Mailpit
(<http://localhost:8025>) and Keycloak (<http://localhost:8280>, users `alice` and `bob`, password
`password`) from `compose.yaml`, as in the web app. The two examples use the same ports: run one at
a time.

## Work on the front end

For hot reload, run the backend with the `vite` profile, and Vite's dev server beside it:

```bash
./mvnw spring-boot:run -pl who-examples/spa -Dspring-boot.run.profiles=vite
cd who-examples/spa/frontend && npm install && npm run dev
```

Then open <http://localhost:5173>. Vite answers the front end itself and forwards `/api` (the app's own)
and `/who` (every who endpoint) to Spring on 8080, keeping the browser's
`Host`. The browser sees one origin, 5173, so cookies, CSRF and same-origin `fetch` work as they do
on 8080. No CORS, and no `SameSite=None`.

### Why the vite profile

who puts some URLs in emails, and checks a passkey's origin, so they must name the port the
browser sees, not the one Spring listens on:

| Setting | Built, on 8080 | `vite` profile, on 5173 |
|---|---|---|
| `who.email-link.link-base-url` | `http://localhost:8080/signin/link` | `http://localhost:5173/signin/link` |
| `who.email-link.address-confirmation-url` | `http://localhost:8080/profile` | `http://localhost:5173/profile` |
| `who.passkey.allowed-origins` | `http://localhost:8080` | `http://localhost:5173` |

The provider settings need no change. `who.oidc.*-url` are paths, and Spring builds the provider's
return address (`{baseUrl}/who/oidc/callback/keycloak`) from the `Host` Vite forwards, so Keycloak
sends the browser back to 5173. The realm allows both. For Google or GitHub, register the 5173
address too (GitHub allows one callback per OAuth app, so make a second app).

The same goes for production behind a proxy or a CDN: these settings name your public origin.

## How the pieces fit

- **Pages are public, data is not.** Any page may be loaded directly (a bookmark, a reload, an
  email's link), so `ClientRoutes` answers `index.html` for each, and `who.security.public-paths`
  lists them with the built files. The data is under `/api`, which who's chain answers with `401`
  when signed out; the front end then goes to `/signin`. Keep the two lists in step.
- **Permissions come from the server.** `GET /api/me` says who is signed in and what they may do.
  The front end hides what they may not do, for convenience only: `@RequiresPermission` checks
  every call.
- **Starting a provider link** is a `fetch` with `Accept: application/json`: who answers where the
  provider is, and the page goes there (`who.oidc.startLink`). A plain form cannot carry the
  CSRF token a SPA holds.
- **Links and tokens in fragments.** A sign-in link (`/signin/link#token=`) and an address
  confirmation (`/profile#confirm-address=`) carry their token after `#`, which never reaches a
  server log. The pages post it once: React's `StrictMode` runs effects twice in development, so
  each page guards against posting a token twice.

## Things to try

1. Open <http://localhost:8080>, enter `admin@example.com`, and open the link from Mailpit.
2. Add a task. Invite `bob@example.com` on Members, and sign in as bob in a private window.
3. On Profile, add a passkey, sign out, and sign in with it.
4. On Profile, link Keycloak as `alice`, and confirm. Sign out and sign in with Keycloak.
5. Sign out, sign in with Keycloak as `bob` (if you did not invite him): the policy lets the local
   realm sign up, so who offers to create the account.
6. On Profile, add or change an email address, and confirm it from Mailpit.
7. Sign in in a private window too. Profile lists both browsers under "Where you're signed in":
   sign the other one out, and its next request is signed out.

## For production

What the [web app's README](../web-app/README.md#for-production) says applies here, and:

- Serve the built front end and the API from one origin, as the jar does; a CDN in front must
  forward who's paths and the `Host`, or the settings above must name it.
- Set `link-base-url`, `address-confirmation-url` and `allowed-origins` to the public origin.
