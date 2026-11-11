# Errors

who formats no error responses. When something goes wrong it throws an exception, and your application answers it the same way it answers its own errors, for example with RFC 9457 Problem Details from a `@RestControllerAdvice`.

## The exceptions

| Exception | Status if unmapped | When |
|---|---|---|
| `SignInFailedException` | 401 | A sign-in failed: a bad, expired or used email-link token; an unknown or invalid passkey; an inactive identity. Its message is always `"Sign-in failed"`, so the reason never reaches a response. The cause keeps it for your logs. |
| `NotSignedInException` | 401 | A request needs a signed-in caller and has none: a protected URL, `GET /who/me`, or passkey registration while signed out. |
| `ReauthenticationRequiredException` | 401 | Passkey registration more than `who.passkey.registration-window` after signing in. Send the user through an email link again, then retry. |
| `InvalidPasskeyRegistrationException` | 400 | A passkey registration failed verification. It carries no cause, because WebAuthn details must not travel. |
| `AccessDeniedException` (Spring Security's own) | 403 | A signed-in caller lacks a permission. Spring Security 7 raises its subclass `AuthorizationDeniedException`. |

All of them are in `org.jwcarman.who.spring.security.web`, except Spring's `AccessDeniedException`.

## How errors raised in filters reach your advice

Most of these errors happen inside Spring Security's filters, before Spring MVC runs, and an exception thrown there never reaches a `@RestControllerAdvice`. who therefore hands each one to your application's Spring MVC exception resolvers, the `handlerExceptionResolver` bean, through `WhoExceptionHandoff`. A controller exception travels the same path, so one advice covers both. This needs Spring MVC, which every Spring Boot servlet application has.

The advice must be global: one limited by `basePackages`, `assignableTypes` or `annotations` never sees who's errors, because they are not raised by a controller. A catch-all `@ExceptionHandler(Exception.class)` sees them too, so map who's exceptions explicitly or it answers them instead.

When nothing in your application handles an exception, who calls `response.sendError(status)` with the status in the table. Spring Boot's own error handling then answers with your application's default error body.

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
    return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "You do not have permission");
  }
}
```

Give each one a `type` URI if your clients tell problems apart by type. Don't put the exception's cause in the response.
