# Errors

**Contents**

- The exceptions
- The `Who-Error` header
- How errors raised in filters reach your advice
- Example: Problem Details

who formats no error responses. When something goes wrong it throws an exception, and your application answers it the same way it answers its own errors, for example with RFC 9457 Problem Details from a `@RestControllerAdvice`.

## The exceptions

| Exception | Status if unmapped | When |
|---|---|---|
| `SignInFailedException` | 401 | A sign-in failed: a bad, expired or used email-link token; an unknown or invalid passkey; an inactive identity. Its message is always `"Sign-in failed"`, so the reason never reaches a response. The cause keeps it for your logs. |
| `NotSignedInException` | 401 | A request needs a signed-in caller and has none: a protected URL, `GET /who/me`, any endpoint under `/who/me`, or an admin endpoint. |
| `ReauthenticationRequiredException` | 401 | A browser session signed in more than `who.security.recent-sign-in` ago tried something that needs a recent sign-in: adding or removing a credential (a passkey, a provider account, an email address), ending another session, making or rotating an API key, changing an identity through the [admin endpoints](admin.md), or an action marked [`@RequiresRecentSignIn`](security-model.md#recent-sign-in). Sign the user in again, then retry. |
| `InsufficientUserAuthenticationException` | 401 | A JWT whose `auth_time` is too old called an action marked `@RequiresRecentSignIn`. A `401` answer carries `WWW-Authenticate: Bearer error="insufficient_user_authentication", …, max_age=<seconds>` (RFC 9470), whether who, your advice or Spring MVC writes it; any other answer carries none. An answer that sets its own `Bearer` challenge (a realm, a scope) keeps it; one challenging with another scheme gets who's beside it. The client signs the person in again and retries. The challenge is also on the exception, `challenge()`. |
| `RecentSignInUnavailableException` | 403 | An API key, or a JWT without an `auth_time` claim, called an action marked `@RequiresRecentSignIn`. Neither can ever be recent, so it is a `403`, not a challenge. It is an `AccessDeniedException`. |
| `InvalidPasskeyRegistrationException` | 400 | A passkey registration failed verification. It carries no cause, because WebAuthn details must not travel. |
| `BrowserSessionRequiredException` | 403 | An endpoint that needs a browser session on its own was called by something else: a bearer token or an API key at a [`/who/me`](account.md) endpoint only a browser may call, a session cookie with an API key or a bearer token beside it at any endpoint that tells the two apart (the `/who/me` endpoints, `POST /who/me/api-keys` and its kin, and an [admin](admin.md) change), or a sign-in no browser session loaded. Send the cookie alone. It is an `AccessDeniedException`. |
| `AccessDeniedException` (Spring Security's own) | 403 | A signed-in caller lacks a permission. Spring Security 7 raises its subclass `AuthorizationDeniedException`. |

Removing a passkey, an email address or a linked account through [`/who/me`](account.md#the-last-credential-rule) adds one, from `who-core`:

| Exception | Status if unmapped | When |
|---|---|---|
| `LastCredentialException` | 409 | The removal would leave the identity with no way to sign in. API keys do not count. |

Provider sign-in adds these, for its [confirm endpoints](social-login.md#sign-up):

| Exception | Status if unmapped | When |
|---|---|---|
| `HumanCheckFailedException` | 403 | The application's [human check](email-links.md#human-check) refused a request to `/who/email/send` or `/who/email/sign-in`, or could not decide it. It is an `AccessDeniedException`. |
| `SignUpRefusedException` | 403 | `POST /who/oidc/pending/sign-up` refused by the sign-up policy, or sent by a browser that is signed in. |
| `UnknownProviderException` | 404 | `POST /who/me/accounts/{registrationId}` named a registration who does not sign in with. |
| `PendingSignUpNotFoundException` | 404 | No pending sign-up, or an expired or used one, at `GET /who/oidc/pending`, `POST /who/oidc/pending/sign-up` or `POST /who/oidc/pending/link`. |
| `AlreadyLinkedException` | 409 | `POST /who/oidc/pending/link` for an account linked to another identity. |
| `LinkedAccountNotFoundException` | 404 | `DELETE /who/me/accounts/{id}` named an account that is not one of the caller's: unknown, malformed or another identity's. |

`POST /who/oidc/pending/link` and `POST /who/me/accounts/{registrationId}`, which starts a link, also answer `NotSignedInException` and `ReauthenticationRequiredException`.

Managing [email addresses](account.md#email-addresses) adds three, besides `NotSignedInException` and `ReauthenticationRequiredException`:

| Exception | Status if unmapped | When |
|---|---|---|
| `EmailCredentialNotFoundException` | 404 | `POST /who/me/email-addresses` named a `replaces`, or `DELETE /who/me/email-addresses/{id}` an id, that is not one of the caller's email addresses. |
| `InvalidEmailAddressConfirmationException` | 400 | `POST /who/me/email-addresses/confirmations` matched nothing: no request by this identity, an expired or used one, a wrong code, a code with no attempts left, or neither or both of `token` and `code`. |
| `EmailAddressTakenException` | 409 | The confirmed address was registered to someone in the meantime, or `POST /who/invitations` named an address that is already an identity's. `EmailCredentialService.register` and `InvitationService.invite` throw it too. |

Managing [API keys](account.md#api-keys) adds three:

| Exception | Status if unmapped | When |
|---|---|---|
| `ApiKeyNotFoundException` | 404 | `POST /who/me/api-keys/{id}/rotations` or `DELETE /who/me/api-keys/{id}` named a key that is not one of the caller's (unknown, malformed or another identity's), or `GET /who/me/api-keys/current` came from a request no API key signed in. |
| `ApiKeyCannotManageKeysException` | 403 | A request an API key signed in called an API-key endpoint other than `GET /who/me/api-keys/current`: a key never lists, makes, rotates or revokes keys. It is an `AccessDeniedException`, and its own code rather than `browser-session-required`, because a bearer token may manage keys too. |
| `InvalidApiKeyRequestException` | 400 | `POST /who/me/api-keys` or a rotation that who refuses as it stands: a body that is not the expected JSON, a `lifetime` or `overlap` that is not an ISO-8601 duration or is out of range, a blank name, no scopes or a scope the owner does not hold, or a key that expired or was revoked before its rotation. |

The [admin endpoints](admin.md) add six, and invitations one more:

| Exception | Status if unmapped | When |
|---|---|---|
| `PermissionRequiredException` | 403 | The caller's permissions lack `who.identities.read` or `who.identities.manage`, whichever the endpoint needs. For an API key, its scopes decide. The message names the permission. |
| `RoleGrantForbiddenException` | 403 | `PUT /who/identities/{id}/roles/{role}` or `POST /who/invitations` would grant a role carrying a permission the caller does not hold itself. |
| `IdentityNotFoundException` | 404 | The path names no identity: unknown, or not a UUID. |
| `UnknownRoleException` | 404 | `PUT` or `DELETE /who/identities/{id}/roles/{role}` named a role that does not exist. |
| `InvalidAdminRequestException` | 400 | A page or size out of range, a status who does not take, a body that is not the expected JSON, or a search by `email=` without email sign-in. The message names the rule broken. |
| `SelfDeactivationException` | 409 | `PATCH /who/identities/{id}` with `DISABLED` named the caller's own identity. |
| `InvalidInvitationException` | 400 | `POST /who/invitations`, or `InvitationService.invite`, with a missing or malformed address, a blank role name or one naming no role, or roles in an application without `who-rbac`. |

`404`s that name no exception (a session or a passkey that is not the caller's) are Spring's `ResponseStatusException`, with no `Who-Error` header.

All of them are in `org.jwcarman.who.spring.security.web`, except Spring's `AccessDeniedException`, `LastCredentialException` (`org.jwcarman.who.core.exception`), the provider exceptions (`org.jwcarman.who.oidc`), the email address and invitation exceptions (`org.jwcarman.who.emaillink`), the API key exceptions (`org.jwcarman.who.apikey`) and the admin exceptions (`org.jwcarman.who.admin`).

## The `Who-Error` header

Every failure in the tables above, except Spring's own `AccessDeniedException`, also names itself in a `Who-Error` response header, whatever body your application writes. A client can tell failures apart by it without knowing your body's shape; who's [TypeScript client](client.md#the-who-error-header) does.

| `Who-Error` | Exception |
|---|---|
| `not-signed-in` | `NotSignedInException` |
| `reauthentication-required` | `ReauthenticationRequiredException` |
| `sign-in-failed` | `SignInFailedException` |
| `insufficient-user-authentication` | `InsufficientUserAuthenticationException` |
| `recent-sign-in-unavailable` | `RecentSignInUnavailableException` |
| `invalid-passkey-registration` | `InvalidPasskeyRegistrationException` |
| `session-not-found` | `SessionNotFoundException` |
| `passkey-not-found` | `PasskeyNotFoundException` |
| `invalid-confirmation` | `InvalidEmailAddressConfirmationException` |
| `email-address-not-found` | `EmailCredentialNotFoundException` |
| `email-address-taken` | `EmailAddressTakenException` |
| `no-pending-sign-up` | `PendingSignUpNotFoundException` |
| `human-check-failed` | `HumanCheckFailedException` |
| `sign-up-refused` | `SignUpRefusedException` |
| `unknown-provider` | `UnknownProviderException` |
| `already-linked` | `AlreadyLinkedException` |
| `linked-account-not-found` | `LinkedAccountNotFoundException` |
| `browser-session-required` | `BrowserSessionRequiredException` |
| `api-key-cannot-manage-keys` | `ApiKeyCannotManageKeysException` |
| `last-credential` | `LastCredentialException` |
| `api-key-not-found` | `ApiKeyNotFoundException` |
| `invalid-api-key-request` | `InvalidApiKeyRequestException` |
| `permission-required` | `PermissionRequiredException` |
| `role-grant-forbidden` | `RoleGrantForbiddenException` |
| `identity-outranks-caller` | `IdentityOutranksCallerException` |
| `identity-not-found` | `IdentityNotFoundException` |
| `unknown-role` | `UnknownRoleException` |
| `invalid-admin-request` | `InvalidAdminRequestException` |
| `self-deactivation` | `SelfDeactivationException` |
| `last-admin` | `LastAdminException` |
| `invalid-invitation` | `InvalidInvitationException` |

The codes are a public contract and do not change between releases. Each exception returns its code from `errorCode()`: the `WhoErrorCode` interface, or, for `LastCredentialException`, which lives in `who-core` without Spring, a method of its own.

who sets the header before handing the exception to your application, so your advice's body and the header arrive together. An advice that calls `response.reset()` clears it. A page on another origin can read it only if your CORS configuration lists it in `Access-Control-Expose-Headers`.

## How errors raised in filters reach your advice

Most of these errors happen inside Spring Security's filters, before Spring MVC runs, and an exception thrown there never reaches a `@RestControllerAdvice`. who therefore hands each one to your application's Spring MVC exception resolvers, the `handlerExceptionResolver` bean, through `WhoExceptionHandoff`. A controller exception travels the same path, so one advice covers both. This needs Spring MVC, which every Spring Boot servlet application has.

The advice must be global: one limited by `basePackages`, `assignableTypes` or `annotations` never sees who's errors, because they are not raised by a controller. A catch-all `@ExceptionHandler(Exception.class)` sees them too, so map who's exceptions explicitly or it answers them instead.

When nothing in your application handles an exception, who calls `response.sendError(status)` with the status in the table, and Spring Boot forwards the request to its error page, `/error`. When that status is a `5xx`, who first logs the exception at `ERROR` with its stack trace, from `WhoExceptionHandoff`. What the client receives then depends on your chain. If `/error` needs a signed-in caller, as it does under `anyRequest().authenticated()`, a signed-out client gets the bare status with an empty body. Permit it (`requestMatchers("/error").permitAll()`) to get Spring Boot's default error body instead.

who's autoconfiguration connects the handoff to your exception resolvers. An application that builds who's beans itself, without the autoconfiguration, gets a handoff with no resolver: it only calls `sendError`, and your advice never sees who's errors. Build one with `new WhoExceptionHandoff((request, response, e) -> resolver.resolveException(request, response, null, e) != null)` over your `handlerExceptionResolver` bean.

## Example: Problem Details

```java
@RestControllerAdvice
class WhoProblems {

  @ExceptionHandler(SignInFailedException.class)
  ProblemDetail signInFailed() {
    return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Sign-in failed");
  }

  @ExceptionHandler(NotSignedInException.class)
  ProblemDetail notSignedIn() {
    return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Sign in to continue");
  }

  @ExceptionHandler(ReauthenticationRequiredException.class)
  ProblemDetail reauthenticate() {
    return ProblemDetail.forStatusAndDetail(
        HttpStatus.UNAUTHORIZED, "Sign in again to register a passkey");
  }

  @ExceptionHandler(InvalidPasskeyRegistrationException.class)
  ProblemDetail invalidRegistration() {
    return ProblemDetail.forStatusAndDetail(
        HttpStatus.BAD_REQUEST, "The passkey could not be registered");
  }

  @ExceptionHandler(AccessDeniedException.class)
  ProblemDetail denied() {
    // a signed-out caller can reach a method-security check when the URL rules let it through
    Authentication caller = SecurityContextHolder.getContext().getAuthentication();
    if (caller == null || caller instanceof AnonymousAuthenticationToken) {
      return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Sign in to continue");
    }
    return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "You do not have permission");
  }
}
```

Give each one a `type` URI if your clients tell problems apart by type. Don't put the exception's cause in the response.
