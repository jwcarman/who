# Errors

**Contents**

- who's answer
- The exceptions
- The `Who-Error` header
- Your own endpoints
- Example: Problem Details for your own endpoints

When something goes wrong, who throws an exception. Who answers it depends on where the request went:

- **Under who's base path** (`who.base-path`, `/who` by default: every who endpoint, Spring Security's sign-in filters included), who answers it itself, with RFC 9457 Problem Details, whatever your application's own error handling says. who's [TypeScript client](client.md) reads one shape.
- **Anywhere else** (your own endpoints, guarded by who's chains), your application answers who's exception the same way it answers its own errors, for example from a `@RestControllerAdvice`. See [Your own endpoints](#your-own-endpoints).

Either way, the `Who-Error` header names the failure.

## who's answer

```http
HTTP/1.1 404 Not Found
Content-Type: application/problem+json
Who-Error: session-not-found

{"type": "urn:who:error:session-not-found", "title": "Not Found", "status": 404,
 "detail": "No session of yours has that id", "code": "session-not-found"}
```

| Member | Value |
|---|---|
| `type` | `urn:who:error:` and the code |
| `title` | the status's reason phrase |
| `status` | the HTTP status |
| `detail` | the exception's message, for a person; `null` for a `5xx`, whose message may carry what a client must not see |
| `code` | the same code as the `Who-Error` header |

Every error under the base path has a code: who's exceptions their own (the tables below), and anything else one named from its status (`400 invalid-request`, `401 unauthorized`, `403 forbidden`, `404 not-found`, `405 method-not-allowed`, `406 not-acceptable`, `415 unsupported-media-type`, any `5xx` `server-error`, any other status `error`). Spring Security's CSRF refusal is `403 invalid-csrf-token`, so a client knows to fetch a new token and retry. A `5xx` is logged at `ERROR` with its stack trace.

The members keep these names and an instant stays a string whatever `spring.jackson` says: who's answers pin their JSON shape on their own types.

## The exceptions

| Exception | Status | When |
|---|---|---|
| `SignInFailedException` | 401 | A sign-in failed: a bad, expired or used email-link token; an unknown or invalid passkey; an inactive identity. Its message is always `"Sign-in failed"`, so the reason never reaches a response. The cause keeps it for your logs. |
| `NotSignedInException` | 401 | A request needs a signed-in caller and has none: a protected URL, `GET /who/me`, any endpoint under `/who/me`, or an admin endpoint. |
| `ReauthenticationRequiredException` | 401 | A browser session signed in more than `who.security.recent-sign-in` ago tried something that needs a recent sign-in: adding or removing a credential (a passkey, a provider account, an email address), ending another session, making or rotating an API key, changing an identity through the [admin endpoints](admin.md), or an action marked [`@RequiresRecentSignIn`](security-model.md#recent-sign-in). Sign the user in again, then retry. |
| `InsufficientUserAuthenticationException` | 401 | A JWT whose `auth_time` is too old called an action marked `@RequiresRecentSignIn`. A `401` answer carries `WWW-Authenticate: Bearer error="insufficient_user_authentication", …, max_age=<seconds>` (RFC 9470), whether who, your advice or Spring MVC writes it; any other answer carries none. An answer that sets its own `Bearer` challenge (a realm, a scope) keeps it; one challenging with another scheme gets who's beside it. The client signs the person in again and retries. The challenge is also on the exception, `challenge()`. |
| `RecentSignInUnavailableException` | 403 | An API key, or a JWT without an `auth_time` claim, called an action marked `@RequiresRecentSignIn`. Neither can ever be recent, so it is a `403`, not a challenge. It is an `AccessDeniedException`. |
| `InvalidPasskeyRegistrationException` | 400 | A passkey registration failed verification. It carries no cause, because WebAuthn details must not travel. |
| `SessionNotFoundException` | 404 | `DELETE /who/me/sessions/{id}` named a session that is not one of the caller's. |
| `BrowserSessionRequiredException` | 403 | An endpoint that needs a browser session on its own was called by something else: a bearer token or an API key at a [`/who/me`](account.md) endpoint only a browser may call, a session cookie with an API key or a bearer token beside it at any endpoint that tells the two apart (the `/who/me` endpoints, `POST /who/me/api-keys` and its kin, and an [admin](admin.md) change), or a sign-in no browser session loaded. Send the cookie alone. It is an `AccessDeniedException`. |
| `AccessDeniedException` (Spring Security's own) | 403 | A signed-in caller lacks a permission. Spring Security 7 raises its subclass `AuthorizationDeniedException`. |

Managing [passkeys](account.md#passkeys) adds one, from `who-passkey`:

| Exception | Status | When |
|---|---|---|
| `PasskeyNotFoundException` | 404 | `DELETE /who/me/passkeys/{credentialId}` named a passkey that is not one of the caller's. |

Removing a passkey, an email address or a linked account through [`/who/me`](account.md#the-last-credential-rule) adds one, from `who-core`:

| Exception | Status | When |
|---|---|---|
| `LastCredentialException` | 409 | The removal would leave the identity with no way to sign in. API keys do not count. |

Provider sign-in adds these, for its [confirm endpoints](social-login.md#sign-up):

| Exception | Status | When |
|---|---|---|
| `HumanCheckFailedException` | 403 | The application's [human check](email-links.md#human-check) refused a request to `/who/email/send` or `/who/email/sign-in`, or could not decide it. It is an `AccessDeniedException`. |
| `SignUpRefusedException` | 403 | `POST /who/oidc/pending/sign-up` refused by the sign-up policy, or sent by a browser that is signed in. |
| `UnknownProviderException` | 404 | `POST /who/me/accounts/{registrationId}` named a registration who does not sign in with. |
| `PendingSignUpNotFoundException` | 404 | No pending sign-up, or an expired or used one, at `GET /who/oidc/pending`, `POST /who/oidc/pending/sign-up` or `POST /who/oidc/pending/link`. |
| `AlreadyLinkedException` | 409 | `POST /who/oidc/pending/link` for an account linked to another identity. |
| `LinkedAccountNotFoundException` | 404 | `DELETE /who/me/accounts/{id}` named an account that is not one of the caller's: unknown, malformed or another identity's. |

`POST /who/oidc/pending/link` and `POST /who/me/accounts/{registrationId}`, which starts a link, also answer `NotSignedInException` and `ReauthenticationRequiredException`.

Managing [email addresses](account.md#email-addresses) adds three, besides `NotSignedInException` and `ReauthenticationRequiredException`:

| Exception | Status | When |
|---|---|---|
| `EmailCredentialNotFoundException` | 404 | `POST /who/me/email-addresses` named a `replaces`, or `DELETE /who/me/email-addresses/{id}` an id, that is not one of the caller's email addresses. |
| `InvalidEmailAddressConfirmationException` | 400 | `POST /who/me/email-addresses/confirmations` matched nothing: no request by this identity, an expired or used one, a wrong code, a code with no attempts left, or neither or both of `token` and `code`. |
| `EmailAddressTakenException` | 409 | The confirmed address was registered to someone in the meantime, or `POST /who/invitations` named an address that is already an identity's. `EmailCredentialService.register` and `InvitationService.invite` throw it too. |

Managing [API keys](account.md#api-keys) adds three:

| Exception | Status | When |
|---|---|---|
| `ApiKeyNotFoundException` | 404 | `POST /who/me/api-keys/{id}/rotations` or `DELETE /who/me/api-keys/{id}` named a key that is not one of the caller's (unknown, malformed or another identity's), or `GET /who/me/api-keys/current` came from a request no API key signed in. |
| `ApiKeyCannotManageKeysException` | 403 | A request an API key signed in called an API-key endpoint other than `GET /who/me/api-keys/current`: a key never lists, makes, rotates or revokes keys. It is an `AccessDeniedException`, and its own code rather than `browser-session-required`, because a bearer token may manage keys too. |
| `InvalidApiKeyRequestException` | 400 | `POST /who/me/api-keys` or a rotation that who refuses as it stands: a body that is not the expected JSON, a `lifetime` or `overlap` that is not an ISO-8601 duration or is out of range, a blank name, no scopes or a scope the owner does not hold, or a key that expired or was revoked before its rotation. |

The [admin endpoints](admin.md) add eight, and invitations one more:

| Exception | Status | When |
|---|---|---|
| `PermissionRequiredException` | 403 | The caller's permissions lack `who.identities.read` or `who.identities.manage`, whichever the endpoint needs. For an API key, its scopes decide. The message names the permission. |
| `RoleGrantForbiddenException` | 403 | `PUT /who/identities/{id}/roles/{role}` or `POST /who/invitations` would grant a role carrying a permission the caller does not hold itself. |
| `IdentityNotFoundException` | 404 | The path names no identity: unknown, or not a UUID. |
| `UnknownRoleException` | 404 | `PUT` or `DELETE /who/identities/{id}/roles/{role}` named a role that does not exist. |
| `InvalidAdminRequestException` | 400 | A page or size out of range, a status who does not take, a body that is not the expected JSON, or a search by `email=` without email sign-in. The message names the rule broken. |
| `SelfDeactivationException` | 409 | `PATCH /who/identities/{id}` with `DISABLED` named the caller's own identity. |
| `IdentityOutranksCallerException` | 403 | A change to another identity that holds a permission the caller does not. |
| `LastAdminException` | 409 | A deactivation or role removal would leave no active identity holding `who.identities.manage`. |
| `InvalidInvitationException` | 400 | `POST /who/invitations`, or `InvitationService.invite`, with a missing or malformed address, a blank role name or one naming no role, or roles in an application without `who-rbac`. |

All of them are in `org.jwcarman.who.spring.security.web`, except Spring's `AccessDeniedException`, `LastCredentialException` (`org.jwcarman.who.core.exception`), `PasskeyNotFoundException` (`org.jwcarman.who.passkey`), the provider exceptions (`org.jwcarman.who.oidc`), the email address and invitation exceptions (`org.jwcarman.who.emaillink`), the API key exceptions (`org.jwcarman.who.apikey`) and the admin exceptions (`org.jwcarman.who.admin`).

## The `Who-Error` header

Every failure under who's base path names itself in a `Who-Error` response header, the same code as the body's `code`. Outside it, every failure in the tables above, except Spring's own `AccessDeniedException`, does too, whatever body your application writes. A client can tell failures apart by it without knowing your body's shape; who's [TypeScript client](client.md#the-who-error-header) reads the header first and falls back to the body's `code`.

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
| `browser-session-required` | `SessionNotFoundException` | 404 | `DELETE /who/me/sessions/{id}` named a session that is not one of the caller's. |
| `BrowserSessionRequiredException` |
| `api-key-cannot-manage-keys` | `ApiKeyCannotManageKeysException` |
| `last-credential` | `LastCredentialException` |
| `api-key-not-found` | `ApiKeyNotFoundException` |
| `permission-required` | `PermissionRequiredException` |
| `role-grant-forbidden` | `RoleGrantForbiddenException` |
| `identity-outranks-caller` | `IdentityOutranksCallerException` |
| `identity-not-found` | `IdentityNotFoundException` |
| `unknown-role` | `UnknownRoleException` |
| `self-deactivation` | `SelfDeactivationException` |
| `last-admin` | `LastAdminException` |
| `invalid-request` | `InvalidAdminRequestException`, `InvalidApiKeyRequestException`, `InvalidInvitationException`; under who's base path, any other `400` |
| `invalid-csrf-token` | Spring Security's CSRF refusal, under who's base path |
| `unauthorized`, `forbidden`, `not-found`, `method-not-allowed`, `not-acceptable`, `unsupported-media-type`, `server-error`, `error` | under who's base path, an error no who exception names, by its status |

The codes are a public contract and do not change between releases. Each exception returns its code from `errorCode()`: the `WhoErrorCode` interface, or, for `LastCredentialException`, which lives in `who-core` without Spring, a method of its own.

Outside the base path, who sets the header before handing the exception to your application, so your advice's body and the header arrive together. An advice that calls `response.reset()` clears it. A page on another origin can read it only if your CORS configuration lists it in `Access-Control-Expose-Headers`.

## Your own endpoints

Outside who's base path, who's exceptions are yours to answer: `NotSignedInException` from who's entry point when a signed-out caller reaches a protected URL, `ReauthenticationRequiredException` from [`@RequiresRecentSignIn`](security-model.md#recent-sign-in), `AccessDeniedException` from your own rules.

Many of these happen inside Spring Security's filters, before Spring MVC runs, and an exception thrown there never reaches a `@RestControllerAdvice`. who therefore hands each one to your application's Spring MVC exception resolvers, the `handlerExceptionResolver` bean, through `WhoExceptionHandoff`. A controller exception travels the same path, so one advice covers both. This needs Spring MVC, which every Spring Boot servlet application has.

The advice must be global: one limited by `basePackages`, `assignableTypes` or `annotations` never sees who's errors from filters, because they are not raised by a controller. Your advice never sees an error under who's base path: who's `WhoErrorResolver` runs first among Spring MVC's resolvers and answers those itself.

When nothing in your application handles an exception, who calls `response.sendError(status)` with the status in the table, and Spring Boot forwards the request to its error page, `/error`. When that status is a `5xx`, who first logs the exception at `ERROR` with its stack trace. What the client receives then depends on your chain. If `/error` needs a signed-in caller, as it does under `anyRequest().authenticated()`, a signed-out client gets the bare status with an empty body. Permit it (`requestMatchers("/error").permitAll()`) to get Spring Boot's default error body instead.

who's autoconfiguration connects the handoff to your exception resolvers and puts `WhoErrorResolver` first. An application that builds who's beans itself, without the autoconfiguration, gets a handoff with no resolver: it only calls `sendError`, and your advice never sees who's errors. Build one with `new WhoExceptionHandoff(paths, (request, response, e) -> resolver.resolveException(request, response, null, e) != null)` over your `handlerExceptionResolver` bean.

## Example: Problem Details for your own endpoints

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
