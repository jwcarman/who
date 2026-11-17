# Admin

`who-admin` serves the endpoints an admin screen needs: list and read identities, deactivate and
reactivate them, assign and remove their roles, list the roles that exist, and invite people by
email address. Each needs a permission who defines and your application grants.

Deleting identities and creating or editing roles are not served over HTTP: roles stay defined in
your code, with `RbacService` (see [RBAC](rbac.md)).

## Turn it on

Add the dependency (its version comes from who's BOM). It brings `who-rbac`, which holds roles:

```xml
<dependency>
    <groupId>org.jwcarman.who</groupId>
    <artifactId>who-admin</artifactId>
</dependency>
```

The endpoints go on every chain who's configurers build, browser and stateless alike.
`who.admin.enabled=false` turns them off without removing the jar. Invitations also need
`who-email-link`; without it there is no `POST /who/invitations`.

**Your authorization rules do not see them.** The endpoints answer from a filter right after
`ExceptionTranslationFilter`, before Spring Security's `AuthorizationFilter`, so rules you write in
`authorizeHttpRequests` (an IP allow-list, `hasRole`, a network check) never run for
`/who/identities`, `/who/roles` or `/who/invitations`; neither do they for who's `/who/me`
endpoints. who's own checks below are what guard them. To put the admin screen behind more than
that, set `who.admin.enabled=false` and serve your own endpoints (see
[Your own admin endpoints](#your-own-admin-endpoints)), or replace the `WhoAdminEndpoints` and
`WhoInvitationEndpoints` beans.

## Permissions

who defines two permissions, as constants in `WhoAdminPermissions`, and grants them to no one:

| Permission | Constant | Allows |
|---|---|---|
| `who.identities.read` | `IDENTITIES_READ` | Listing and reading identities, and listing roles |
| `who.identities.manage` | `IDENTITIES_MANAGE` | Deactivating and reactivating identities, assigning and removing roles, and inviting |

Give them to a role of yours like any other permission:

```java
Role admin = rbacService.createRole("admin");
rbacService.addPermissionToRole(admin, WhoAdminPermissions.IDENTITIES_READ);
rbacService.addPermissionToRole(admin, WhoAdminPermissions.IDENTITIES_MANAGE);
```

Any who sign-in that carries the permission may call the endpoints: a browser session, a bearer
token, or an API key whose scopes include it. An API key has only the permissions its scopes keep,
not all of its owner's.

## Who may call what

Every admin endpoint checks, in this order, before it looks at anything the request names:

1. Signed out: `401` `NotSignedInException`.
2. The permission is missing: `403` `PermissionRequiredException` (`Who-Error: permission-required`),
   which names it.
3. A request that changes something, from a browser session that signed in longer ago than
   `who.security.recent-sign-in`: `401` `ReauthenticationRequiredException`. A bearer token or an
   API key needs no recent sign-in. Reads never do.

Then an unknown identity or role is `404`, and a query or body who refuses is `400`
(`InvalidAdminRequestException`, which names the rule broken). A session cookie with an `X-API-Key`
or `Authorization: Bearer` header beside it is neither a browser session nor a stateless caller:
it may read, but a change from it is `403` `BrowserSessionRequiredException`
(`Who-Error: browser-session-required`), even when the header is ignored: send the cookie alone.

### Changing an identity

A change to an existing identity (`PATCH` its status, `PUT` or `DELETE` one of its roles) is then
checked, in this order, before anything changes:

1. The identity outranks the caller: `403` `IdentityOutranksCallerException`
   (`identity-outranks-caller`). The caller must hold every permission the identity holds, as who
   resolves them for it whatever its status, so a manager cannot deactivate, reactivate or change
   the roles of an owner who holds more than they do. The caller's permissions are those of its
   sign-in, so an API key counts only what its scopes keep. Acting on yourself always passes.
2. Assigning a role the caller could not hold: `403` `role-grant-forbidden` (see
   [Roles](#roles)).
3. Deactivating yourself: `409` `self-deactivation`.
4. Leaving no admin: `409` `LastAdminException` (`last-admin`), described next.

### The last admin

Deactivating an identity, or removing a role from one, is refused with `409`
`LastAdminException` (`last-admin`) when it would leave no `ACTIVE` identity holding
`who.identities.manage`. That covers removing your own admin role when no one else holds it, and
deactivating the only other holder. It applies only to a change from one or more holders to none:
when no one holds the permission, nothing is refused.

The count and the change run in one transaction that first locks the `who_role` rows of every role
carrying `who.identities.manage` (`SELECT ... FOR UPDATE`), so two changes cannot both count before
either commits: two admins demoting each other at the same moment leave one admin, not none. A
concurrent assignment of one of those roles waits for the lock. `LastAdminGuard` is the bean that
does this, in a `READ COMMITTED` transaction whatever the database's default; it needs exactly one
`PlatformTransactionManager`, on the `DataSource` of who's `JdbcClient` (see
[Transactions and locks](database.md#transactions-and-locks)), and without one the admin endpoints
fail startup rather than run without the rule.

who counts holders from who-rbac's tables, the active identities assigned a role that carries the
permission, because that is who holds it when who-rbac resolves permissions. With a
`PermissionsResolver` of your own (marked `@Primary` over who-rbac's), who cannot tell who holds
the permission, so the rule refuses nothing rather than refuse on a count it cannot trust, and who
logs a warning at startup: `who: the last-admin rule is off`. Keep an admin of your own in that
case. Changes made in your own code through `RbacService` or `WhoService.deactivate` are not
checked.

| Endpoint | Permission | Success |
|---|---|---|
| `GET /who/identities` | read | `200`, a page of identities |
| `GET /who/identities/{id}` | read | `200`, one identity |
| `PATCH /who/identities/{id}` | manage | `200`, the identity after the change |
| `PUT /who/identities/{id}/roles/{role}` | manage | `204` |
| `DELETE /who/identities/{id}/roles/{role}` | manage | `204` |
| `GET /who/roles` | read | `200`, every role |
| `POST /who/invitations` | manage | `201` |

The browser's POST, PATCH, PUT and DELETE need the CSRF header, as every change does.

## Identities

An identity looks like this:

```json
{
  "id": "0f8e5c1a-6d1b-4e55-9a49-4c9f3a0a7d2e",
  "status": "ACTIVE",
  "createdAt": "2026-11-16T09:12:44Z",
  "roles": ["admin", "editor"],
  "emailAddresses": ["alice@example.com"],
  "credentials": { "email": 1, "passkey": 2, "account": 0 }
}
```

`status` is `ACTIVE`, `SUSPENDED` or `DISABLED`. `roles` and `emailAddresses` are sorted;
`emailAddresses` is empty without email sign-in. `credentials` counts the sign-in credentials by
kind, as [`GET /who/me`](account.md#who-is-signed-in) does, and is empty when who cannot count them.

`GET /who/identities` pages through identities, oldest first:

```text
GET /who/identities?status=ACTIVE&role=editor&email=alice@example.com&page=0&size=20
```

```json
{ "items": [ ... ], "page": 0, "size": 20, "total": 1 }
```

- `page` starts at 0 (the default); `size` runs from 1 to 100, 20 by default. Anything else, or a
  value that is not a whole number, is `400`.
- `status` is an exact status name; any other value is `400`.
- `role` is a role's name. A role nobody holds, or one that does not exist, gives an empty page.
- `email` finds the identity that signs in with that address, matched as email sign-in matches it.
  It is `400` without email sign-in.
- Given together, every filter must match. A blank parameter counts as absent. `total` is how many
  identities match, on every page.

`GET /who/identities/{id}` answers one identity. An unknown id, or one that is not a UUID, is `404`
`IdentityNotFoundException` (`identity-not-found`).

### Deactivating and reactivating

`PATCH /who/identities/{id}` takes a status:

```json
{ "status": "DISABLED" }
```

`DISABLED` deactivates the identity with `WhoService.deactivate`: no credential resolves to it, so
its sessions, tokens and keys stop working on their next request, until `ACTIVE` reactivates it
with `WhoService.reactivate`. Any other value, `SUSPENDED` included, is `400`. The answer is the
identity after the change.

An admin cannot deactivate themselves: `DISABLED` on the caller's own id is `409`
`SelfDeactivationException` (`self-deactivation`). `ACTIVE` on oneself changes nothing.

## Roles

`GET /who/roles` lists every role, by name, with its permissions sorted, so an admin screen can
offer them:

```json
[{ "name": "editor", "permissions": ["task.read", "task.write"] }]
```

`PUT /who/identities/{id}/roles/{role}` assigns a role and `DELETE` removes it, both by the role's
name and both `204` whether or not anything changed. A role that does not exist is `404`
`UnknownRoleException` (`unknown-role`).

**No self-escalation.** A caller may assign only a role whose every permission it holds itself;
otherwise the answer is `403` `RoleGrantForbiddenException` (`role-grant-forbidden`), and nothing
changes. Its permissions are those of its sign-in, so an API key may grant only what its scopes
keep. A role with no permissions may be granted by anyone who may manage identities. Removing a
role is not restricted.

## Invitations

Inviting someone creates an identity for their address, gives it roles, and emails it a sign-in
link. Accepting the invitation is signing in.

`POST /who/invitations`:

```json
{ "email": "bob@example.com", "roles": ["editor"] }
```

answers `201`:

```json
{ "identityId": "0193f2a4-...", "linkSent": true }
```

`roles` may be left out for none. `linkSent` is `false` when the link could not be sent; the
invitation stands, and the person can still sign in by asking for a link on the sign-in page.

It needs `who.identities.manage` and, from a browser session, a recent sign-in, checked as above.
Then:

| Status | `Who-Error` | When |
|---|---|---|
| `400` | `invalid-admin-request` | The body is not a JSON object, `email` is not a string, or `roles` is not an array of strings |
| `400` | `invalid-invitation` | The address is missing or malformed, a role name is blank or names no role, or roles were asked for without `who-rbac` |
| `403` | `role-grant-forbidden` | A role carries a permission the caller does not hold, as for assigning one |
| `409` | `email-address-taken` | The address is already an identity's, or a concurrent invite of the same address took it first |

Nothing is created in any of these cases.

An exception from your [`IdentityInvited`](events.md#identityinvited-runs-inside-the-invitation)
listener rolls the invitation back and goes through your exception handling, like every who
failure (see [Errors](errors.md)), even a `DuplicateKeyException` from your own tables, which is
never reported as `email-address-taken`: a `@RestControllerAdvice` of yours answers it, or the
exception's own `@ResponseStatus` does. A listener that refuses an invitation can say why:

```java
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
class SeatLimitReachedException extends RuntimeException {}

@EventListener
void on(IdentityInvited event) {
  if (seats.full()) {
    throw new SeatLimitReachedException(); // POST /who/invitations answers 422, nothing created
  }
}
```

An exception nothing answers and that carries no status is `500`, and who logs it at `ERROR`
with its stack trace, since no handler of yours saw it.

### `InvitationService`

The endpoint is a thin layer over `InvitationService`, a bean of `who-email-link` that your own
code can call:

```java
Invitation invitation = invitationService.invite("bob@example.com", Set.of("editor"));
invitation.identityId();
invitation.linkSent();     // only EmailLinkOutcome.SENT counts
invitation.linkOutcome();
```

It applies no role-grant policy: it trusts its caller, as `RbacService` does. It guarantees this
order:

1. In one transaction: the identity is created, the address registered, each role assigned, and
   [`IdentityInvited`](events.md#identityinvited-runs-inside-the-invitation) published,
   synchronously, inside the transaction. If any step throws, a listener included, everything
   rolls back and no link is sent.
2. Only after that commits, the sign-in link is issued by `EmailLinkIssuer.issue`, with
   no transaction open, so a link is only ever sent for an identity that exists. A link that is
   not sent is logged at WARN, without the address or the token.

**Call `invite` outside any transaction.** Called inside one, it throws `IllegalStateException`:
the link must go after the invitation commits, and a transaction of yours would hold the
invitation back while the link went out. To store your own data for the new person, such as a
profile or a team membership, listen for `IdentityInvited`. The listener runs inside the
invitation's transaction, so its writes commit or roll back with it:

```java
@Service
class Members {

  private final InvitationService invitations;
  private final MembershipRepository memberships;

  Members(InvitationService invitations, MembershipRepository memberships) {
    this.invitations = invitations;
    this.memberships = memberships;
  }

  // no @Transactional here
  UUID invite(String email, TeamRole role) {
    return invitations.invite(email, Set.of(role.name())).identityId();
  }

  @EventListener
  void on(IdentityInvited event) {
    // joins the invitation's transaction: saved only if the invitation is
    memberships.save(event.identityId(), event.roles());
  }
}
```

`InvitationService` needs a single `PlatformTransactionManager` too. Without one, who builds
neither it nor `POST /who/invitations`, but the application does not get that far: the rest of
the admin endpoints fail startup for want of `LastAdminGuard`. An application that supplies its
own `LastAdminGuard` (or `WhoAdminEndpoints`) bean starts, and then has no invitation endpoint
unless it also defines an `InvitationService` bean. Roles need `who-rbac`: without
it, an invitation with roles is refused with `InvalidInvitationException` before anything is
created, and one without roles still works. `who-rbac` is optional for `who-email-link`; the
role assignment goes through the `InvitationRoles` interface, which who's autoconfiguration
implements with `RbacInvitationRoles` when `who-rbac` is present.

## Your own admin endpoints

The beans behind the endpoints are `WhoAdminEndpoints` and `WhoInvitationEndpoints`; each is
replaced by a bean of your own of the same type. To serve your own admin pages instead, use
`WhoService`, `RbacService`, `IdentityRepository.findPage` and `InvitationService` directly, and
check permissions with `@RequiresPermission` and a recent sign-in with
[`@RequiresRecentSignIn`](security-model.md#recent-sign-in).
