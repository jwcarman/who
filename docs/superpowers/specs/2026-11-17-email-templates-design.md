# Email templates and one seam for sending

Status: approved by the owner. Date: 2026-11-17. Target: 0.23.0.

## Intent

An application should be able to change what who's emails say and how they look, in its own
words and its own brand, without writing Java. It should also be able to send them through any
provider (SMTP today; SES, SendGrid, Postmark later) without rewriting their content.

Today neither works well:

- `MailWhoEmailSender` hard-codes plain-text bodies. Only the subjects can be configured, through
  four properties.
- An app that sends through another provider writes its own `WhoEmailSender`. That makes it
  responsible for every email's content too.
- who-passkey's "a passkey was added" email (`MailPasskeyAddedNotifier`) skips the seam entirely
  and talks to `JavaMailSender` directly. An app with a custom sender still sends that one over
  SMTP, or not at all.

## Goals

- **One seam.** Every email who sends goes through `WhoEmails`, with no exceptions (owner's rule).
  An email that goes around it is a bug.
- **Never inside a transaction.** No email is sent while a database transaction is open (owner's
  rule). The seam enforces this once, for every email.
- **who renders, senders deliver.** `WhoEmailSender` only delivers a finished email. Content is
  rendered by who from templates.
- **Templates are overridable per email.** An app drops a file named after the email to replace
  who's text, and may add an HTML version.
- **Subjects localize** through Spring's `MessageSource`.
- **Senders are modules.** Put a jar on the classpath and that sender is on. SMTP ships now, and
  other providers follow as their own modules.

## Non-goals

- New sender modules (SES, SendGrid, Postmark) in this release. The design makes them small; each
  comes later.
- Storing a person's preferred language. who keeps no locale per identity.
- Template logic beyond Mustache: no conditionals or formatting in templates.
- Previewing or editing templates over HTTP.

## Anti-goals

- Any who email sent without going through `WhoEmails`.
- Any who email sent while a transaction is open.
- A homegrown template engine.
- Two ways to configure the same thing, for example a subject property *and* a message key.

## Approaches considered

1. **A sealed `WhoEmail` and every email kind in one new `who-email` module.** This keeps the
   compile-time list of every kind. It moves feature types out of their features, and the reason
   for sealing disappears once senders stop switching over kinds. Rejected.
2. **The seam in who-core, kinds beside their features, rendering in `who-email`, one module per
   sender.** Chosen by the owner. It scales with senders and kinds, and every module can send
   without new dependencies. It loses the compile-time list of kinds, and tests and startup checks
   make up for that.
3. **Template engine.** The options were Thymeleaf (Spring Boot's most common choice, used by
   Spring Boot Admin and JHipster for mail), Mustache via JMustache (supported by Spring Boot,
   tiny, logic-less), and a homegrown placeholder engine. The owner chose **Mustache**. The
   homegrown engine is an anti-goal.

## The design

### The seam: who-core, package `org.jwcarman.who.core.email`, no new dependencies

```java
/** An email who sends. Each kind is a record in the module that owns the feature. */
public interface WhoEmail {
  /** The address the email goes to. */
  String address();
  /** The template's name, stable and kebab-case: "sign-in", "invitation", "passkey-added". */
  String template();
}

/** A rendered email, ready to deliver. */
public record OutgoingEmail(
    String to, String subject, String text, @Nullable String html, WhoEmail source) {}

@FunctionalInterface
public interface WhoEmailRenderer {
  /** Renders the email in the locale; throws if its template is missing or fails. */
  OutgoingEmail render(WhoEmail email, Locale locale);
}

@FunctionalInterface
public interface WhoEmailSender {
  /** Delivers the email; throws if it could not, so who can undo what the email promised. */
  void send(OutgoingEmail email);
}

/** Renders and sends who's emails. Features send only through this. */
public final class WhoEmails {
  public WhoEmails(WhoEmailRenderer renderer, WhoEmailSender sender, Supplier<Locale> locale);
  /**
   * Renders the email in the current locale and sends it.
   *
   * @throws IllegalStateException if a transaction is open
   * @throws RuntimeException whatever the renderer or sender threw
   */
  public void send(WhoEmail email);
}
```

- `WhoEmails` is a concrete final class. It is the one place that guarantees render, then send,
  and the transaction check. Apps replace the renderer or the sender, never `WhoEmails`.
- `OutgoingEmail.html` is null when no HTML template exists, and the sender then sends text only.
- `OutgoingEmail.source` is the typed email, for senders that use a provider's hosted templates,
  keyed by `source().template()`.
- There is no sender address on `OutgoingEmail`. The sending address is configuration (see
  Configuration).
- The transaction check uses `TransactionSynchronizationManager.isActualTransactionActive()`.

### Email kinds stay with their features

`WhoEmail` is no longer sealed. Each kind is a record beside its feature, and its components are
what templates can use:

| Kind | Module | Template |
|---|---|---|
| `EmailLinkMessage` | who-email-link | `sign-in` |
| `InvitationMessage` | who-email-link | `invitation` |
| `EmailAddressConfirmationMessage` | who-email-link | `address-confirmation` |
| `EmailAddressChangedMessage` | who-email-link | `address-changed` |
| `PasskeyAddedMessage` (new) | who-passkey | `passkey-added` |

Templates are logic-less, so records expose display-ready values next to raw ones: for example
`formattedCode()` (which exists) and `expiresIn()` ("15 minutes"). An accessor a template needs is
part of the record's documented API.

`MailPasskeyAddedNotifier` is removed, and who-passkey sends `PasskeyAddedMessage` through
`WhoEmails`. Whether `PasskeyAddedNotifier` survives as an internal seam is left to the builder.
No app-facing way of sending outside `WhoEmails` may remain.

### Rendering: the `who-email` module

The default `WhoEmailRenderer` uses JMustache.

- **The model.** Each template sees two names:
  - `email`: the record itself. JMustache resolves `{{email.link}}` through the record accessor
    (verified: its `DefaultCollector` tries the exact method name first).
  - `app`: application-wide values from `who.email.model.*`, such as `app.name` and
    `app.supportAddress`.

  Per-email values an app adds itself (for example the recipient's display name) go under a third
  name, `extra`. The hook that supplies them is left to the builder, as an optional bean.
- **Templates are found by name, the app's first.**
  - The text template is `<template>.txt`. It must exist, and who ships one for every kind.
  - The HTML template is `<template>.html`. It is optional; when it exists, the mail is
    multipart.
  - The lookup order is the app's location (`who.email.templates`), then who's built-in templates
    inside the jar. A file in the app's location replaces who's file of the same name.
  - who ships text templates only (my call). The look of HTML mail is the app's to decide.
- **Partials.** `{{> footer}}` resolves from the same locations, so an app can share a header or
  footer across emails.
- **Escaping follows the file.**
  - `.html` templates HTML-escape values (JMustache's default).
  - `.txt` templates do not escape, so a link's `&` stays intact.
- **Missing values fail.** A variable the model doesn't have throws, rather than rendering empty.
  The render fails, the sender is never called, and the feature reports the email as not sent.
- **Subjects** come from the `MessageSource` under the key `who.email.<template>.subject`, with
  `{0}`-style arguments filled from the model where useful. The app's messages come first, then a
  bundle shipped in `who-email`. This replaces the four subject properties.
- **Locale.** The `Supplier<Locale>` returns the request's locale when one is bound
  (`LocaleContextHolder`), else `who.email.locale` (default: the JVM's). Sign-in links are issued
  off the request thread, so that path captures the request's locale before handing off.
- **Startup checks.**
  - Every built-in and every overriding template compiles at startup, so a syntax error fails
    startup and doesn't surface at the first send.
  - who's own tests render every built-in template against a real record, so who's defaults can't
    drift from the records.

### Delivery: one module per sender

- **`who-email-smtp`** is today's SMTP sender: `MailWhoEmailSender` renamed `SmtpWhoEmailSender`
  and moved. It builds a MIME message, multipart when `html` is present, through Spring's
  `JavaMailSender`.
- **Later**, `who-email-ses`, `who-email-sendgrid` and `who-email-postmark` each implement
  `WhoEmailSender` in a few lines.
- **Jar on, sender on.** If a sender module is on the classpath and configured, its bean exists.
  An app may also define its own `WhoEmailSender` bean, and every auto-configured sender backs off.
- **A feature that must send email** (who-email-link) fails startup when there is no
  `WhoEmailSender` or no renderer, naming what's missing. This follows who's module-activation
  rule: missing required configuration fails startup.
- **A feature for which email is optional** (who-passkey's notice) sends when `WhoEmails` exists,
  and otherwise sends nothing, logging so once at startup.

### Configuration

| Property | Meaning |
|---|---|
| `who.email.from` | The sending address, used by every sender. It replaces `who.email-link.from` and `who.passkey.from`. |
| `who.email.templates` | The app's template location. Its default is left to the builder (for example `classpath:/templates/who/email/`). |
| `who.email.locale` | The locale used when no request locale is bound. |
| `who.email.model.*` | App-wide template values, exposed as `{{app.*}}`. |

These are removed, with no aliases:
- `who.email-link.subject`
- `who.email-link.invitation-subject`
- `who.email-link.address-confirmation-subject`
- `who.email-link.address-changed-subject`
- `who.email-link.from`
- `who.passkey.from`

## Breaking changes (document in the CHANGELOG and upgrading.md)

- **The seam moves.** `WhoEmailSender` moves to who-core, and it takes `OutgoingEmail` instead of
  `WhoEmail`. A custom sender becomes pure delivery.
- **`WhoEmail`** moves to who-core, is no longer sealed, and gains `template()`. The kind records
  stay in their packages.
- **`MailWhoEmailSender` is now `SmtpWhoEmailSender`,** in the new `who-email-smtp` module. Apps
  add `who-email` and `who-email-smtp`; whether the existing starter brings them in is left to the
  builder.
- **Subject and from properties** are replaced by message keys and `who.email.from`.
- **`MailPasskeyAddedNotifier` is removed.** The passkey notice goes through the seam.
- **The sign-in, address and invitation emails** are rendered from templates. The default wording
  stays as close to today's as the templates allow; any change is listed.

## Constraints

- Java 25 and Spring Boot 4. No star imports, no warning suppression, no fully qualified names.
- **Native image.** JMustache reads records and loads templates reflectively and from resources.
  The native tests must send each email, and who-email ships the reachability metadata that needs.
- **JMustache's version** comes from Spring Boot's dependency management.
- **Who sends.** Callers of `WhoEmails` must not hold a transaction. Existing callers already
  send after commit; the check makes it a rule.

## Left to the builder

- The default `who.email.templates` location, and the names of the built-in bundle and template
  paths inside the jar.
- How the per-email `extra` model is supplied (an optional bean is suggested).
- How the locale is captured across the sign-in path's thread hand-off.
- Whether who's starter includes `who-email` and `who-email-smtp` by default.
- Module layout of the auto-configuration (who-autoconfigure, as today, is expected).
