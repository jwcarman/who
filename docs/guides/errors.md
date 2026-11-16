# Errors

who formats no error responses. When something goes wrong it throws an exception, and your application answers it the same way it answers its own errors, for example with RFC 9457 Problem Details from a `@RestControllerAdvice`.

## The exceptions

| Exception | Status if unmapped | When |
|---|---|---|
| `SignInFailedException` | 401 | A sign-in failed: a bad, expired or used email-link token; an unknown or invalid passkey; an inactive identity. Its message is always `"Sign-in failed"`, so the reason never reaches a response. The cause keeps it for your logs. |
| `NotSignedInException` | 401 | A request needs a signed-in caller and has none: a protected URL, `GET /who/me`, or passkey registration while signed out. |
| `ReauthenticationRequiredException` | 401 | Adding a credential (a passkey, a provider account, an email address) more than `who.security.recent-sign-in` after signing in. Sign the user in again, then retry. |
| `InvalidPasskeyRegistrationException` | 400 | A passkey registration failed verification. It carries no cause, because WebAuthn details must not travel. |
| `AccessDeniedException` (Spring Security's own) | 403 | A signed-in caller lacks a permission. Spring Security 7 raises its subclass `AuthorizationDeniedException`. |

Provider sign-in adds four, for its [confirm endpoints](social-login.md#sign-up):

| Exception | Status if unmapped | When |
|---|---|---|
| `SignUpRefusedException` | 403 | `POST /who/oidc/sign-up` refused by the sign-up policy, or sent by a browser that is signed in. |
| `UnknownProviderException` | 404 | `POST /who/oidc/link/{registrationId}` named a registration who does not sign in with. |
| `PendingSignUpNotFoundException` | 404 | No pending sign-up, or an expired or used one, at `GET /who/oidc/pending`, `POST /who/oidc/sign-up` or `POST /who/oidc/link`. |
| `AlreadyLinkedException` | 409 | `POST /who/oidc/link` for an account linked to another identity. |

`POST /who/oidc/link` and `POST /who/oidc/link/{registrationId}`, which starts a link, also answer `NotSignedInException` and `ReauthenticationRequiredException`.

Adding or changing an [email address](email-links.md#adding-or-changing-an-address) adds three, besides `NotSignedInException` and `ReauthenticationRequiredException`:

| Exception | Status if unmapped | When |
|---|---|---|
| `EmailCredentialNotFoundException` | 404 | `POST /who/email/addresses` named a `replaces` that is not one of the caller's email addresses. |
| `InvalidEmailAddressConfirmationException` | 400 | `POST /who/email/addresses/confirm` matched nothing: no request by this identity, an expired or used one, a wrong code, a code with no attempts left, or neither or both of `token` and `code`. |
| `EmailAddressTakenException` | 409 | The confirmed address was registered to someone in the meantime. `EmailCredentialService.register` throws it too. |

All of them are in `org.jwcarman.who.spring.security.web`, except Spring's `AccessDeniedException`, the provider sign-in exceptions, which are in `org.jwcarman.who.oidc`, and the email address exceptions, which are in `org.jwcarman.who.emaillink`.

## How errors raised in filters reach your advice

Most of these errors happen inside Spring Security's filters, before Spring MVC runs, and an exception thrown there never reaches a `@RestControllerAdvice`. who therefore hands each one to your application's Spring MVC exception resolvers, the `handlerExceptionResolver` bean, through `WhoExceptionHandoff`. A controller exception travels the same path, so one advice covers both. This needs Spring MVC, which every Spring Boot servlet application has.

The advice must be global: one limited by `basePackages`, `assignableTypes` or `annotations` never sees who's errors, because they are not raised by a controller. A catch-all `@ExceptionHandler(Exception.class)` sees them too, so map who's exceptions explicitly or it answers them instead.

When nothing in your application handles an exception, who calls `response.sendError(status)` with the status in the table, and Spring Boot forwards the request to its error page, `/error`. What the client receives then depends on your chain. If `/error` needs a signed-in caller, as it does under `anyRequest().authenticated()`, a signed-out client gets the bare status with an empty body. Permit it (`requestMatchers("/error").permitAll()`) to get Spring Boot's default error body instead.

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
