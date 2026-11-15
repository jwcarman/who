# Example: a server-rendered web app

A small team task board built on who. People sign in with an email link or a typed code, a
passkey, or an identity provider (a local Keycloak always; Google and GitHub when you give it
credentials). The app has no `SecurityFilterChain` of its own: who's default chain does all of
it. The app's own security code is one sign-up policy (`SignUpConfig`) and `@RequiresPermission`
on its controllers.

## Run it

You need Java 25 and Docker. From the repository root:

```bash
./mvnw install -DskipTests
./mvnw spring-boot:run -pl who-examples/web-app
```

Spring Boot's Docker Compose support starts what the app needs from `compose.yaml` and stops it
when the app stops:

| Service | Where | What for |
|---------|-------|----------|
| Postgres | a free port Boot picks | who's tables and the tasks |
| Mailpit | <http://localhost:8025> | the inbox every email lands in |
| Keycloak | <http://localhost:8280> (admin / admin) | an OpenID Connect provider with users `alice` and `bob`, password `password` |

Then open <http://localhost:8080>.

## Things to try

1. **Sign in as the admin.** On first start the app creates `admin@example.com` with the admin
   role. Enter that address, open the email in Mailpit, and click the link, or type the code.
2. **Invite someone.** On Members, invite `bob@example.com`. who sends links only to addresses it
   knows, so this is how a new person gets in by email.
3. **Add a passkey.** On Profile, name it and add it, then sign out and sign in with the passkey.
   Registering needs a sign-in in the last 10 minutes.
4. **Sign up with Keycloak.** Sign out, choose Sign in with Keycloak, and sign in as `alice`. who
   does not know the account, so it asks whether to create one. The app's sign-up policy allows
   accounts from this Keycloak only, and makes them members.
5. **Link an account.** Signed in as the admin, choose Link Keycloak on Profile. Keycloak asks
   for a password even if it remembers someone, because who asks it to, so you see which account
   you are linking. Sign in as `bob` and confirm, and bob's Keycloak account now signs in as the admin. An account that
   already belongs to someone else, such as alice's, is refused with `already-linked` and changes
   nothing. Choosing Sign in with Keycloak while signed in never links; it says to sign out first.

   Signing out of the app does not sign you out of Keycloak: it remembers who signed in last, and
   the next Sign in with Keycloak (not a link) goes straight through as that user. To be someone
   else, sign out at <http://localhost:8280/realms/who-example/account> or use a private window.
6. **See a closed provider.** With Google or GitHub turned on, sign in with an account no one has
   linked: sign-up is refused, because the policy opens only Keycloak.

## Google and GitHub

Each is a Spring profile, so its registration exists only when you have credentials:

```bash
export GOOGLE_CLIENT_ID=... GOOGLE_CLIENT_SECRET=...
export GITHUB_CLIENT_ID=... GITHUB_CLIENT_SECRET=...
./mvnw spring-boot:run -pl who-examples/web-app -Dspring-boot.run.profiles=google,github
```

Register `http://localhost:8080/login/oauth2/code/google` (or `/github`) as the redirect URI with
the provider.

## Where to look

| File | What it shows |
|------|---------------|
| `application.yml` | everything who needs: the email link, passkey and provider settings, and the public paths |
| `SignUpConfig.java` | a sign-up policy: open to one provider, closed to the rest |
| `Setup.java`, `Invitations.java` | creating identities, registering email addresses, assigning roles |
| `BoardController.java`, `MembersController.java` | `@RequiresPermission` on pages and actions |
| `ProfileController.java` | listing an identity's credentials, unlinking a provider account with an ownership check |
| `WebErrors.java` | sending a signed-out browser to the sign-in page instead of a bare `401` |
| `static/js/who.js` | the browser calls from the who guides: CSRF, email links, passkeys, provider sign-up and linking, sign-out |

## For production

- Serve it over HTTPS, and set `who.passkey.rp-id` and `allowed-origins` to your host.
- Create who's tables with Flyway or Liquibase and set `who.initialize-schema: never`.
- Take `who.email-link.code-secret` and the client secrets from your secret store.
- Point `spring.mail` at a real mail service, and rate-limit `/ott/generate` and `/login/ott` at
  the ingress.
- Remove `trust-email` unless the provider is yours.
