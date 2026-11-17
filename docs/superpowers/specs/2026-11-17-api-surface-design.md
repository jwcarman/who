# Who's HTTP API surface: consistent, complete, REST

Status: draft for the owner's approval. Date: 2026-11-17.

## Intent

Applications built on who keep writing the same endpoints over who's services: listing passkeys,
unlinking provider accounts, managing API keys, drawing the sign-in page, inviting and managing
members. Several of those services check no ownership (`PasskeyService.revoke`,
`FederatedCredentialService.unlink`, `ApiKeyService.revoke`), so every app also re-implements a
security check. Who should serve these itself, under one consistent REST layout, so an app builds
its UI against who and writes only what is genuinely its own.

Prompted by roux's dojo (jwcarman/roux `examples/dojo`), which builds a passkey list and an
invitation flow, and by who's own examples, which build API key, linked-account and member
endpoints.

## Goals

- Every endpoint follows REST conventions: plural nouns for collections; `GET` lists, `POST`
  creates, `DELETE /{id}` removes, `PATCH` updates; no verbs as resource names.
- Two halves, by purpose:
  - **Sign-in flows** keep a singular namespace per method: `/who/email`, `/who/passkey`,
    `/who/oidc`. Their protocol steps (`sign-in`, `callback`) stay as action paths.
  - **The caller's own account** lives under `/who/me`. The path says "only your own", and every
    endpoint there checks ownership itself.
- **Admin endpoints** for managing identities live under `/who/identities` and
  `/who/invitations`, guarded by permissions who defines.
- Self-service removal of a credential never leaves an identity unable to sign in.
- `@jwcarman/who-client` covers the whole surface.

## Non-goals

- Profiles, display names, activity logs, exports, themes: application data. Roux takes these.
- Self-service account deletion. The owner ruled that deactivation by an admin is enough.
- Deleting identities, and creating or editing roles, over HTTP. Roles stay defined in code
  (`RbacService`, typed vocabularies).
- Compatibility shims for moved paths. Who's users are the owner and one friend; breaking changes
  are documented, not aliased.

## Anti-goals

- An admin endpoint reachable without an explicit permission, or a self-service endpoint that acts
  on another identity's data.
- A secret (API key) shown more than once, or ever logged.
- Two app-visible ways to do the same thing.

## Approaches considered

1. **Per-method namespaces everywhere** (`/who/passkey/credentials`, `/who/email/addresses`,
   `/who/sessions`). Fewest paths move, but account management stays in three places and
   ownership is implicit.
2. **`/who/me` for the caller's own things, method namespaces for sign-in flows.** Chosen by the
   owner: it separates signing in from managing an account, and makes the ownership rule visible
   in the path.
3. **Admin HTTP endpoints deferred, services only.** Rejected by the owner: who ships admin
   endpoints with its own permissions.

## The surface

`<root>` is `who.base-path`, `/who` by default. "Browser" means a who browser session; an API key
or bearer token beside the cookie does not count (the rule `SessionsFilter` applies today).
"Recent" means a sign-in within `who.security.recent-sign-in`; otherwise `401`
`ReauthenticationRequiredException`.

### Sign-in (unchanged except where noted)

| Endpoint | Notes |
|---|---|
| `GET /who/sign-in` | **New**, public. Which methods are on and the providers to draw buttons for: `{"email": true, "passkey": true, "providers": [{"id": "github", "name": "GitHub"}]}`. |
| `POST /who/email/send`, `POST /who/email/sign-in` | Unchanged. |
| `POST /who/passkey/sign-in/options`, `POST /who/passkey/sign-in` | Unchanged. |
| `GET /who/oidc/authorize/{registrationId}`, `GET /who/oidc/callback/{registrationId}` | Unchanged (protocol). |
| `GET /who/oidc/pending` | Unchanged. |
| `POST /who/oidc/pending/sign-up` | Was `POST /who/oidc/sign-up`. |
| `POST /who/oidc/pending/link` | Was `POST /who/oidc/link`, which collided with starting a link. |
| `POST /who/logout` | Unchanged (Spring's convention). |

### The caller's account: `/who/me`

| Endpoint | Who may call | Notes |
|---|---|---|
| `GET /who/me` | Any signed-in caller | **Extended**: `identityId`, `permissions`, `signInMethod` of this request, `recentSignInUntil` (browser only, else null), and credential counts. |
| `GET /who/me/sessions` | Browser | Was `/who/sessions`. |
| `DELETE /who/me/sessions/{id}` | Browser | Was `/who/sessions/{id}`; same rules. |
| `DELETE /who/me/sessions?others=true` | Browser, recent | Was `/who/sessions`. |
| `GET /who/me/passkeys` | Browser | **New.** `credentialId`, `label`, `created`, `lastUsed`, oldest first. No internal UUID. |
| `POST /who/me/passkeys/options`, `POST /who/me/passkeys` | Browser, recent | Were `/who/passkey/register/options` and `/who/passkey/register`. |
| `DELETE /who/me/passkeys/{credentialId}` | Browser, recent | Was `/who/passkey/register/{id}`. Last-credential rule. |
| `GET /who/me/email-addresses` | Signed in | **New.** |
| `POST /who/me/email-addresses` | Browser, recent | Was `/who/email/addresses`; same body, including `replaces`. |
| `POST /who/me/email-addresses/confirmations` | Browser | Was `/who/email/addresses/confirm`. |
| `DELETE /who/me/email-addresses/{id}` | Browser, recent | **New.** Last-credential rule. |
| `GET /who/me/accounts` | Signed in | **New.** Linked provider accounts: id, provider, linked time. |
| `POST /who/me/accounts/{registrationId}` | Browser, recent | Was `POST /who/oidc/link/{registrationId}`: starts the link redirect. |
| `DELETE /who/me/accounts/{id}` | Browser, recent | **New.** Unlink. Last-credential rule. |
| `GET /who/me/api-keys` | Browser or bearer token | **New.** |
| `POST /who/me/api-keys` | Browser (recent) or bearer token | **New.** Name, lifetime, scopes. The raw key appears in this response only. |
| `POST /who/me/api-keys/{id}/rotations` | Browser (recent) or bearer token | **New.** Wraps `ApiKeyService.rotate`; the new raw key appears once. |
| `DELETE /who/me/api-keys/{id}` | Browser or bearer token | **New.** |
| `GET /who/me/api-keys/current` | API key | **New.** The key this request used: its scopes and expiry, never another key. |

A request authenticated by an API key may not list, create, rotate or revoke keys: a key never
mints keys. Each `/who/me` endpoint lives in the module that owns the data (passkeys in
`who-passkey`, addresses in `who-email-link`, accounts in `who-oidc`, keys in `who-apikey`); jar on
means endpoint on.

An id that is not the caller's answers `404`, exactly as an unknown id does.

### Admin: `/who/identities`, `/who/invitations`

Who defines two permissions, assigned to roles like any other:

- `who.identities.read`: list and read identities.
- `who.identities.manage`: invite, deactivate and reactivate, assign and remove roles.

| Endpoint | Permission | Notes |
|---|---|---|
| `GET /who/identities` | read | Paged (`?page`, `?size`), filterable by `status` and `role`, searchable by email address (`?email=`). Each: id, status, created, roles, email addresses, credential counts. |
| `GET /who/identities/{id}` | read | One identity, same shape. |
| `PATCH /who/identities/{id}` | manage | `{"status": "ACTIVE" \| "INACTIVE"}`. Calls `WhoService.deactivate`/`reactivate`. An admin cannot deactivate themselves (`409`). |
| `PUT /who/identities/{id}/roles/{role}` | manage | Assigns a role; idempotent. |
| `DELETE /who/identities/{id}/roles/{role}` | manage | Removes a role; idempotent. |
| `GET /who/roles` | read | The roles that exist, with their permissions, so an admin UI can offer them. |
| `POST /who/invitations` | manage | `{"email": "...", "roles": ["..."]}` → `201` with `identityId` and whether the link was sent. `409` `EmailAddressTakenException` for a taken address. |

Admin endpoints accept any authentication that carries the permission, including an API key
scoped to it.

Most admin endpoints need `who-rbac`, which holds roles; without it `/who/identities` lists
identities but has no role filter or role endpoints. Invitations need `who-email-link`.

### Invitations as a service

`InvitationService.invite(String email, Set<String> roles)` in `who-email-link` does what the
dojo's `InvitationService` does, in the same order:

1. in one transaction: create the identity, register the address, assign the roles, publish
   `IdentityInvited(identityId, address, roles)` so the app can attach its own data in the same
   transaction;
2. after commit: issue the sign-in link (`EmailLinkIssuer.issue`) in its own transaction, so a link
   is only ever sent for an identity that exists.

It returns `Invitation(identityId, EmailLinkOutcome)`. `POST /who/invitations` is a thin endpoint
over it.

## The last-credential rule

Removing a passkey, an email address or a linked account answers `409 LastCredentialException`
when it would leave the identity with no sign-in credential. Email addresses, passkeys and linked
accounts count; API keys do not, because they sign in a machine, not the person.

The count and the delete happen in one transaction that locks the identity's row
(`SELECT … FOR UPDATE`), so two concurrent removals cannot both pass the check. This applies to
the self-service endpoints. The services keep their existing contract, and their Javadoc says that
callers must apply the rule themselves.

## Errors

New exceptions go through `WhoExceptionHandoff`, like the existing ones: `LastCredentialException`
(409), and the admin `403`/`404` cases. `docs/guides/errors.md` lists them.

## What moves (breaking)

The paths in the tables marked "Was …" move, with no aliases. The CHANGELOG's "Breaking changes"
section gives an old → new table. The TS client changes method names where its namespaces change
(`passkeys`, `sessions` and `email` move under `me`). Roux's dojo is told when the release ships.

## Left to the builder

- Response field names beyond those given, following the existing session view's style.
- Module and class placement for the admin endpoints (a `who-admin` module, or each in its owning
  module); the rule "jar on = endpoint on" must hold either way.
- The paging response shape (`items`, `page`, `size`, `total` is the suggestion).
- Test layout, following each module's existing filter tests.

## Open questions for the owner

None. The owner decided: the `/who/me` layout, `409` on removing the last credential, no
self-service deletion, and admin endpoints with who-owned permissions.

Decided by me, open to correction:

- every credential removal needs a recent sign-in (today Spring's passkey delete does not);
- a key never manages keys;
- an admin cannot deactivate themselves;
- `confirm` and `rotate` became the nouns `confirmations` and `rotations`.
