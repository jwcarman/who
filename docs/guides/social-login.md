# Social Login

`who-oidc` lets people sign in with Google, GitHub or any OpenID Connect provider. Spring
Security's `oauth2Login` speaks the protocol; who decides which identity a provider account
belongs to, whether an account it does not know may create one, and gives the sign-in a who
session, events and metrics like every other method.

A provider account is the credential `(issuer, subject)`, the same one JWT sign-in uses, so a
browser sign-in and an API call from the same provider can reach the same identity (see
[one identity from browser and API](#one-identity-from-browser-and-api)).

## What who will not do

These are deliberate, and no setting turns them on:

- **who never signs in, links or matches by email address.** Two accounts with the same address
  at two providers are two credentials. An address is something a provider says about an account,
  not who the account is.
- **who never trusts the issuer a token names for itself.** The issuer comes from your
  configuration, and an ID token whose `iss` differs is refused.
- **Sign-up is closed by default.** An account who does not know creates nothing until your
  [sign-up policy](#open-sign-up) allows it.
- **A provider callback alone never creates an identity or links an account.** The person
  confirms first, on your page.
- **who keeps nothing of the provider's in the session**: no `OAuth2AuthenticationToken`, no
  `OidcUser`, no ID token, no access token.
- **An email address reaches your policy only when a provider trusted for email marks it
  verified.**

## Turn it on

Add the dependency (its version comes from who's BOM):

```xml
<dependency>
    <groupId>org.jwcarman.who</groupId>
    <artifactId>who-oidc</artifactId>
</dependency>
```

Then register providers the way Spring Boot already does, under
`spring.security.oauth2.client.registration`. Every registration with the `authorization_code`
grant signs in; `client_credentials` and other grants are left alone, so registrations your app
uses to call other APIs are untouched. To leave one out, set
`who.oidc.providers.<registration-id>.enabled=false`.

With `who-oidc` on the classpath and no sign-in registration, startup fails and names
`spring.security.oauth2.client.registration`. `who.oidc.enabled=false` turns provider sign-in off
without removing the jar. `who-oidc` does not turn JWT sign-in on: that is `who-jwt`'s jar.

The provider's callback is Spring's default, `{baseUrl}/login/oauth2/code/{registrationId}`, such
as `https://app.example.com/login/oauth2/code/google`. Register that URL with the provider.

### Google

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          google:
            client-id: ${GOOGLE_CLIENT_ID}
            client-secret: ${GOOGLE_CLIENT_SECRET}
```

Spring Boot knows Google's endpoints and its issuer, `https://accounts.google.com`. who trusts
Google's verified email addresses.

### GitHub

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          github:
            client-id: ${GITHUB_CLIENT_ID}
            client-secret: ${GITHUB_CLIENT_SECRET}
            scope: read:user,user:email
```

GitHub is OAuth 2, not OpenID Connect, so it has no issuer of its own. who recognizes github.com by
its endpoints (`github.com` and `api.github.com`), not by the registration's name, and uses the
issuer `https://github.com` and GitHub's numeric user `id` as the subject. The `login` changes when
someone renames their account, so it is never the subject, whatever Boot's `user-name-attribute`
says.

Add `user:email` to the scope so who can read the account's primary, verified address from
`/user/emails`. Without it, or when that call fails or takes more than 3 seconds, the person still
signs in; your policy just sees no verified email.

GitHub Enterprise gets no default. Configure it as [any other OAuth 2
provider](#an-oauth-2-provider-without-openid-connect).

### Any OpenID Connect provider

Give the provider's issuer and ask for the `openid` scope:

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          keycloak:
            client-id: app
            client-secret: ${KEYCLOAK_CLIENT_SECRET}
            scope: openid,email,profile
        provider:
          keycloak:
            issuer-uri: https://sso.example.com/realms/main
```

A provider configured by its endpoints instead of `issuer-uri` needs its issuer from you, since
Spring would otherwise check none:

```yaml
spring:
  security:
    oauth2:
      client:
        provider:
          keycloak:
            authorization-uri: https://sso.example.com/realms/main/protocol/openid-connect/auth
            token-uri: https://sso.example.com/realms/main/protocol/openid-connect/token
            jwk-set-uri: https://sso.example.com/realms/main/protocol/openid-connect/certs
            user-info-uri: https://sso.example.com/realms/main/protocol/openid-connect/userinfo
who:
  oidc:
    providers:
      keycloak:
        issuer: https://sso.example.com/realms/main
```

The ID token's `sub` is the subject. who trusts the email addresses of Google and github.com only.
To pass another provider's verified addresses to your policy, set
`who.oidc.providers.<id>.trust-email=true`, and only for a provider that verifies every address
itself and lets no one claim an address they do not own.

Single-tenant Microsoft Entra ID works with its tenant's issuer,
`https://login.microsoftonline.com/<tenant-id>/v2.0`. The shared `common`, `organizations` and
`consumers` endpoints fail startup: multi-tenant Entra is not supported yet. Neither is Sign in
with Apple.

### An OAuth 2 provider without OpenID Connect

Name the issuer, any stable string that identifies the provider, and the user-info attribute that
holds the account's immutable id:

```yaml
who:
  oidc:
    providers:
      gitlab:
        issuer: https://gitlab.example.com
        subject-attribute: id
```

Startup fails until both are set. Choose an attribute the person cannot change; never a username
or an email address.

### Startup checks

Startup fails, naming the property to fix, when:

- an OpenID Connect registration has neither `issuer-uri` nor `who.oidc.providers.<id>.issuer`;
- an OAuth 2 registration other than github.com lacks `issuer` or `subject-attribute`;
- two sign-in registrations have the same issuer;
- an issuer is one of Entra's shared endpoints;
- `who.oidc.success-url` or `who.oidc.failure-url` is not a path on this site (it must start with
  `/`, not `//`, and have no scheme).

## The sign-in page

Each provider's button is a plain link:

```html
<a href="/oauth2/authorization/google">Sign in with Google</a>
<a href="/oauth2/authorization/github">Sign in with GitHub</a>
```

The provider sends the person back to who, and who redirects:

| Outcome | Redirect |
|---|---|
| Signed in | `who.oidc.success-url` (default `/`) |
| An account who does not know | `who.oidc.failure-url?error=no-account` |
| An account linked to another identity, reached while signed in | `who.oidc.failure-url?error=already-linked` |
| Anything else: cancelled, refused by the provider, a bad token, an inactive identity | `who.oidc.failure-url?error=signin-failed` |

The default for `who.oidc.failure-url` is `/`. Point it at your sign-in page and list that page in
`who.security.public-paths`: who does not open it for you, and a signed-out person sent there would
get a `401`. Startup logs a warning when the failure URL is not public. The reason behind
`signin-failed` is never in the URL; it is in the [events](#events-and-metrics).

who opens `/oauth2/authorization/**`, `/login/oauth2/code/**`, `GET /who/oidc/pending` and
`POST /who/oidc/sign-up` to signed-out requests. The sign-in is a session sign-in, like an email
link: the session id changes, the CSRF token rotates, and the `who_last_sign_in` cookie records the
registration id (see [Sessions](sessions.md)).

### Sign up

On `error=no-account`, who has kept a pending sign-up in the session: the provider account, nothing
created yet. It lasts `who.oidc.sign-up-window` (default 10 minutes) and can be used once. Ask
who about it, and ask the person whether they want an account:

```js
const params = new URLSearchParams(location.search);
if (params.get('error') === 'no-account') {
  const response = await fetch('/who/oidc/pending');
  if (response.ok) {
    const pending = await response.json();
    // { "provider": "github", "displayName": "octocat", "canSignUp": true, "canLink": false }
    if (pending.canSignUp) {
      showConfirm(`Create an account with ${pending.provider} as ${pending.displayName}?`);
    }
  }
}

async function confirmSignUp() {
  const response = await post('/who/oidc/sign-up');   // the CSRF helper from the Passkeys guide
  if (response.ok) {
    location.assign('/');            // signed in; the body is { "identityId": "..." }
  } else if (response.status === 403) {
    showMessage('Sign-up is not open to this account.');
  }
}
```

`displayName` is the account's `email` or `preferred_username` for OpenID Connect and the `login`
for GitHub, so the person sees which account they are about to use. `POST /who/oidc/sign-up` (CSRF
protected, like every POST) asks your [policy](#open-sign-up). `Allow` creates the identity, links
the account and signs in, in one transaction. `Refuse` answers `403` with `SignUpRefusedException`.
No pending sign-up, or an expired one, is `404` with `PendingSignUpNotFoundException`. A browser
that is signed in cannot sign up (`403`). Answers go through your exception handling (see
[Errors](errors.md)).

One click is fine for sign-up: a landing page may call `POST /who/oidc/sign-up` as soon as it sees
`error=no-account`. Your policy still decides.

If two requests confirm the same account at once, one identity is created and both sign in to it.

### Link an account to someone signed in

A signed-in person links a provider account by starting the same sign-in, from your account
settings:

```html
<a href="/oauth2/authorization/github">Link your GitHub account</a>
```

who asks OpenID Connect providers to show their account chooser (`prompt=select_account`), so the
person picks the account rather than being signed in with whichever one the provider remembers.
The callback lands on `error=no-account` with a pending sign-up for which `canLink` is `true`. Show
which account it is, and on confirmation:

```js
const response = await post('/who/oidc/link');   // { "credentialId": "..." }
```

`POST /who/oidc/link` links the account and publishes `CredentialAdded`. It needs the person to
have signed in within `who.oidc.link-window` (default 10 minutes), or it answers `401` with
`ReauthenticationRequiredException`; sign them in again and retry. An account already linked to
another identity is `409` with `AlreadyLinkedException`. Signed out is `401`; no pending sign-up
is `404`.

**Never confirm a link without asking.** The confirmation is what stops a planted account: someone
who gets your user's browser to complete a provider sign-in with the attacker's account would
otherwise attach it to your user's identity, and then sign in as them. Always show the account and
wait for a click.

**Notify the person when a credential is added**, by email, as `PasskeyAddedNotifier` does for
passkeys. Listen for `CredentialAdded` with kind `FEDERATED` (see [Events](events.md)).

A pending sign-up remembers who was signed in when it started, and is dropped when anyone else
signs in, including someone signing in where no one was. An account started in a shared browser
cannot be linked or signed up by the next person.

Reaching an account that is already linked to someone else while signed in redirects with
`error=already-linked` and changes nothing: the session stays as it was. To switch accounts, sign
out first.

To remove a provider account from an identity, call `FederatedCredentialService.unlink` with the
credential's id from your own endpoint; it publishes `CredentialRevoked`.

## Open sign-up

who's default policy, `ClosedSignUpPolicy`, refuses everyone. Define your own `WhoSignUpPolicy`
bean to decide:

```java
@Bean
WhoSignUpPolicy whoSignUpPolicy(RbacService rbacService) {
    ClosedSignUpPolicy closed = new ClosedSignUpPolicy();
    return request -> {
        if ("google".equals(request.registrationId())
                && "example.com".equals(request.claims().get("hd"))) {
            return SignUpDecision.allow(identity -> rbacService.assignRoleByName(identity, "MEMBER"));
        }
        return closed.decide(request);
    };
}
```

`SignUpRequest` carries the method (`OIDC`), the registration id, the issuer as your configuration
names it, the subject, the provider's simple-valued claims (text, true/false and numbers, read-only), and `verifiedEmail`, filled only
for a provider trusted for email that marks the address verified (`email_verified` for OpenID
Connect, GitHub's primary and verified address). `SignUpDecision.allow(setup)` runs `setup` on the
new identity in the transaction that creates it: if it throws, nothing is created.

**Allow-list an organization by a claim the provider vouches for, never by email domain.** Google
Workspace accounts carry `hd`, the Workspace domain; Entra ID tokens carry `tid`, the tenant. A
verified `@example.com` address at another provider proves only that the person could read that
mailbox once, and it outlives their employment.

A refused sign-up publishes `SignInFailed` with reason `SIGN_UP_REFUSED`.

## Recover a duplicate account

who never merges identities. Someone who signed up with an email link and later chooses "Sign in
with Google" on another device gets a second identity, if your policy allows it. The
`who_last_sign_in` cookie lets your sign-in page mark the method a browser used last, which
prevents most of these.

To fix one, delete the newer identity with `WhoService.deleteIdentity`, which revokes its
credentials, then have the person sign in to the original identity and link the provider account
as above.

## One identity from browser and API

A browser sign-in and an API call through [JWT](jwt.md) reach the same identity only when the
provider puts the same `iss` and `sub` in its ID tokens and in the access tokens your API receives:

| Provider | Same identity? |
|---|---|
| Keycloak, Auth0 | Yes: access tokens carry the same issuer and subject |
| Okta | Check your authorization server: a custom server's access tokens can carry the user's login as `sub` rather than the ID token's user id |
| Microsoft Entra ID | Only for the same app registration: Entra's `sub` is different for each application |
| Google | No: Google's access tokens are not JWTs |
| GitHub | No: GitHub issues no JWTs |

`who-jwt` requires `spring.security.oauth2.resourceserver.jwt.audiences` for exactly this reason:
without it, an ID token the same provider issued to any other application would sign in as the
person's identity. See [JWT](jwt.md#validate-the-audience).

## Events and metrics

| Outcome | Event |
|---|---|
| Signed in, by provider or by confirming a sign-up | `SignInSucceeded`, method `OIDC` |
| Cancelled, refused by the provider, a state mismatch, a bad or foreign ID token | `SignInFailed`, reason `PROVIDER_ERROR` |
| The provider returned no subject | `SignInFailed`, reason `MISSING_CLAIMS` |
| The account's identity is inactive | `SignInFailed`, reason `INACTIVE_IDENTITY` |
| The account's identity no longer exists | `SignInFailed`, reason `UNKNOWN_CREDENTIAL` |
| The policy refused a sign-up | `SignInFailed`, reason `SIGN_UP_REFUSED` |
| A sign-up | `IdentityCreated` and `CredentialAdded` (kind `FEDERATED`, labelled with the issuer) |
| A link | `CredentialAdded` (kind `FEDERATED`) |

Starting a pending sign-up and `already-linked` publish nothing. The `who.sign-ins` meter counts
these with `method=oidc` (see [Observability](observability.md)).

## Test it

`who-test` has `StubOidcProvider`, an OpenID Connect provider on `http://127.0.0.1:<port>` for
integration tests:

```java
static final StubOidcProvider PROVIDER = StubOidcProvider.start();

@DynamicPropertySource
static void provider(DynamicPropertyRegistry registry) {
    registry.add("spring.security.oauth2.client.provider.test.issuer-uri", PROVIDER::issuer);
}
```

Register `test` with a client id, a secret and the `openid` scope. Before each sign-in, say who the
provider signs in with `PROVIDER.nextUser("alice", Map.of("email", "alice@example.com",
"email_verified", true))`. Start at `/oauth2/authorization/test` in your test client, follow its
redirect to the stub's `/authorize` with any HTTP client, and send the `Location` it answers (the
callback, with `code` and `state`) back to your app in the same session. `nextIssuerClaim` forges
the ID token's `iss`, to prove a token from the wrong issuer is refused.

`StubOidcProvider.startGitHub()` serves GitHub's `/user` and `/user/emails` instead, with
`nextEmails`, `failEmails` and `delayEmails` to shape the email lookup. Its endpoints are on
`127.0.0.1`, so configure them as an [OAuth 2 provider](#an-oauth-2-provider-without-openid-connect)
with an `issuer` and `subject-attribute: id`.

See [Testing](testing.md) for `WhoMockMvcBrowser`, which keeps the session and CSRF token across
those requests.

## Configuration

| Property | Default | Description |
|---|---|---|
| `who.oidc.enabled` | `true` | Turns provider sign-in off when `false`, with `who-oidc` still on the classpath |
| `who.oidc.success-url` | `/` | Where a provider sign-in lands. A path on this site |
| `who.oidc.failure-url` | `/` | Where every other outcome lands, with `error=<code>`. A path on this site; list it in `who.security.public-paths` |
| `who.oidc.sign-up-window` | `10m` | How long a pending sign-up waits for the person to confirm it |
| `who.oidc.link-window` | `10m` | How recently the identity must have signed in to link an account |
| `who.oidc.providers.<id>.enabled` | `true` | Leaves one registration out of sign-in when `false` |
| `who.oidc.providers.<id>.issuer` | none | The issuer, for an OpenID Connect provider configured by endpoints or an OAuth 2 provider |
| `who.oidc.providers.<id>.subject-attribute` | none | The user-info attribute holding the account's immutable id, for an OAuth 2 provider other than github.com |
| `who.oidc.providers.<id>.trust-email` | `true` for Google and github.com, else `false` | Whether a verified address from this provider reaches your sign-up policy |
