# Explicit account linking in who-oidc

## Intent

Today a provider sign-in becomes a link because of who is signed in when the provider calls back:
an unknown account plus a signed-in session means "link". The person never said so. A signed-in
person who clicks "Sign in with Keycloak" is offered a link they did not ask for, and an
application cannot tell a sign-in from a link until the callback. Linking should start only when
the person asks for it, and because linking adds a way into an identity, it should demand more
than signing in does.

The owner chose this on 2026-11-15 after the web-app example showed the confusion. who is a
security library and wants the secure way first; breaking changes are acceptable and documented.

## Goals

- A link starts only from an explicit, CSRF-protected request naming the provider.
- A plain provider sign-in never links and never offers to.
- A link makes the provider authenticate the person again, where the provider supports it.
- The link intent cannot be forged, reused, or carried over to another sign-in or another
  identity.

## Non-goals

- Changing sign-up. A signed-out sign-in with an unknown account still lands on `no-account`
  with `canSignUp`.
- An unlink endpoint (apps still call `FederatedCredentialService.unlink`).
- Notifying the person of a new link. Worth doing; a separate change.

## Anti-goals

- No link intent in a URL, query parameter or cookie a third party could set.
- No session flag that outlives one authorization round trip.
- Dropping the confirmation step. It stays.

## The design

### Starting a link

`POST /who/oidc/link/{registrationId}`, CSRF-protected like every POST, signed-in callers only
(`401 NotSignedInException` otherwise; `404` for a registration who does not sign in with).

1. Build the authorization request with who's resolver, the two-argument
   `resolve(request, registrationId)`, with a link customizer that:
   - puts two **attributes** on the `OAuth2AuthorizationRequest`: the intent `link` and the
     signed-in identity id. Attributes stay on the server; they are never sent to the provider;
   - for an OpenID Connect provider, adds `max_age=0` and `prompt=login` to the parameters sent
     to the provider.
2. Save it with Spring's `AuthorizationRequestRepository` (the session, as today).
3. Answer with a redirect to the request's authorization URI.

An app's "Link Keycloak" button becomes a small form (or a `fetch` that follows the answer):

```html
<form method="post" action="/who/oidc/link/keycloak"><button>Link Keycloak</button></form>
```

### The callback

Spring removes the saved request when the provider calls back, matching `state`, then drops it
when it builds the `OAuth2AuthenticationToken`. who reads the attributes on the way: either
by wrapping the `AuthorizationRequestRepository` so `removeAuthorizationRequest` hands the
removed request to who for this HTTP request, or by an object post-processor on
`OAuth2LoginAuthenticationFilter` (who already post-processes it for the issuer check) whose
result converter keeps the attributes. Builder's call; the wrapper is simpler to test. Built: a result converter on the filter, returning
a `LinkingAuthenticationToken` that carries the intent, since who already post-processes that
filter. The link request uses the sign-in callback (`{action}` = `login`); Spring's two-argument
resolve would otherwise fill Boot's default redirect URI with `authorize`.

| Intent | Account is | Signed in now | Result |
|---|---|---|---|
| none | yours | yes or no | signed in, `success-url` (as today) |
| none | someone else's | yes | `already-linked`, nothing changed (as today) |
| none | unknown | no | pending sign-up, `no-account`, `canSignUp` (as today) |
| none | unknown | yes | **new:** `failure-url?error=signed-in`; nothing kept. Sign out first |
| link | unknown | same identity as the intent | pending link, `no-account`, `canLink` |
| link | yours | same identity | nothing to do, `success-url`; the session keeps its credential (not a sign-in) |
| link | someone else's | same identity | `already-linked`, nothing changed |
| link | any | a different identity, or signed out | `signin-failed`, nothing kept |

### The fresh login

For a link through an OpenID Connect provider, who sends `max_age=0` and requires the ID token's
`auth_time` to be recent (within a minute or two of the callback, allowing clock skew). The OIDC
spec requires `auth_time` when `max_age` is sent, so a provider that remembered the person
without asking fails the check: `signin-failed`, reason `PROVIDER_ERROR`. `prompt=login` is sent
too, for providers that honour it but are lax about `max_age`.

For an OAuth 2 provider without OpenID Connect (GitHub), there is no `auth_time`. The existing
`who.oidc.link-window` (sign-in recency of the who session) remains the check there. The builder
confirms whether GitHub honours a parameter that forces its login screen and adds it if so.

### Confirming

Unchanged: `GET /who/oidc/pending` shows `canLink`, `POST /who/oidc/link` links after the
existing checks (same identity, link window, not linked elsewhere).

### What goes away

`SelectAccountWhenSignedIn`'s `prompt=select_account` for a signed-in plain sign-in: a plain
sign-in by someone signed in no longer links, so it has nothing to choose for. Deferred minor 1
from the social-login review (its two-argument `resolve` returning null) is fixed by being used.

## Breaking changes

None for users: who-oidc is new in the unreleased 0.18.0, so the CHANGELOG entry describes the
explicit link instead. Compared with the unreleased code:

- A link starts from `POST /who/oidc/link/{registrationId}`, not from a plain link to
  `/oauth2/authorization/{id}` while signed in.
- A signed-in plain provider sign-in with an unknown account answers `error=signed-in`.
- Links through OpenID Connect providers require a fresh provider login.

## Tests (who-autoconfigure, against `StubOidcProvider`)

- a link started by POST, with a fresh `auth_time`, links after confirmation;
- a link with a stale `auth_time` is refused;
- a plain sign-in while signed in with an unknown account gets `error=signed-in` and keeps nothing;
- a link started by one identity and called back while another is signed in links nothing;
- `POST /who/oidc/link/{id}` without a CSRF token is `403`; signed out is `401`;
- the intent does not survive: a link round trip that is abandoned, then a plain sign-in, does not
  link;
- native: the attributes serialize with the authorization request (`OidcRuntimeHints`).

## Then

The web-app example's profile page uses the form, and `docs/guides/social-login.md` "Link an
account to someone signed in" is rewritten around it.

## Left to the builder

- Wrapper versus post-processor for reading the attributes.
- The exact `auth_time` tolerance.
- The attribute key names (under a `who.` prefix).
