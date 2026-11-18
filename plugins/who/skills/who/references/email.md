# Email

**Contents**

- Turn it on
- What who sends
- Templates
- Subjects
- Languages
- Sending through something else
- Never inside a transaction
- Native images

who sends a few emails of its own: sign-in links and codes, invitations, address confirmations and
the security notices that follow some changes. Every one of them goes through one class,
`WhoEmails`, which does three things in order:

1. refuses if a database transaction is open;
2. renders the email, with a `WhoEmailRenderer`, into a subject, a text body and an optional HTML
   body;
3. hands the result, an `OutgoingEmail`, to a `WhoEmailSender`, which only delivers it.

Rendering and delivery are separate modules, so you can change what the emails say without
touching how they go out, and the reverse.

| Module | What it does |
|---|---|
| `who-email` | Renders who's emails from Mustache templates you can override. |
| `who-email-smtp` | Delivers them over SMTP, through Spring Boot's `JavaMailSender`. |

## Turn it on

A module that sends email ([email links](email-links.md), and the
[passkey-added notice](passkeys.md#the-passkey-added-email)) needs both halves. Add them next to
it (their versions come from who's BOM):

```xml
<dependency>
    <groupId>org.jwcarman.who</groupId>
    <artifactId>who-email</artifactId>
</dependency>
<dependency>
    <groupId>org.jwcarman.who</groupId>
    <artifactId>who-email-smtp</artifactId>
</dependency>
```

and tell it where to send from:

```yaml
spring:
  mail:
    host: smtp.example.com
who:
  email:
    from: no-reply@example.com
```

`who-email-smtp` brings `spring-boot-starter-mail`. With it on the classpath and a mail host set,
startup fails if `who.email.from` is missing. Email-link sign-in fails startup when there is no
way to send its emails, and the message says which half is missing. The passkey-added notice is
optional: without `WhoEmails` it is not sent, and who logs that once at startup.

## What who sends

| Template | Module | Record | Sent when |
|---|---|---|---|
| `sign-in` | `who-email-link` | `EmailLinkMessage` | someone asks for a sign-in link |
| `invitation` | `who-email-link` | `InvitationMessage` | an [invitation](admin.md#invitations) is made |
| `address-confirmation` | `who-email-link` | `EmailAddressConfirmationMessage` | someone asks to [add or change an address](email-links.md#adding-or-changing-an-address) |
| `address-changed` | `who-email-link` | `EmailAddressChangedMessage` | an address is replaced; it goes to the old one |
| `passkey-added` | `who-passkey` | `PasskeyAddedMessage` | a passkey is added to an account |

Each record implements `WhoEmail`: `address()` is the recipient, and `template()` names the
template above.

## Templates

who ships a plain-text template for every email, in its own words. To use yours, put a file with
the template's name in `classpath:/templates/who/email/`. `who.email.templates` is a list of
Spring resource locations, searched in order, to look elsewhere or in several places (a `file:`
location lets you edit templates without a rebuild):

```properties
who.email.templates=classpath:/templates/who/email/,file:./email/
```

Each location is one place, so use `classpath:`, not `classpath*:`: who looks a file up in each
location in turn, and a `classpath*:` location never matches.

| File | Effect |
|---|---|
| `sign-in.txt` | replaces who's text for sign-in emails |
| `sign-in.html` | adds an HTML body; the email becomes multipart, with who's text (or yours) as the alternative |

A file you don't provide comes from who, so you can replace one email, or only add HTML to one.
Templates are [Mustache](https://mustache.github.io/mustache.5.html), and every template is
compiled at startup, so one that doesn't compile fails startup rather than the first send.

A template sees three names:

- `email`: the record, so `{{email.link}}` is `EmailLinkMessage.link()`;
- `app`: your values from `who.email.model.*`, so `who.email.model.support-address` is
  `{{app.support-address}}`;
- `extra`: values your `WhoEmailModelContributor` beans add for each email (below).

A variable the model doesn't have fails the render: the email is not sent, and the feature reports
it as not sent. A value that is present but null renders empty. `.html` templates escape what they
insert; `.txt` templates do not, so a link's `&` stays as it is.

For example, `src/main/resources/templates/who/email/sign-in.html`:

```html
<p>Hello from {{app.name}}.</p>
<p><a href="{{email.link}}">Sign in</a>, or enter <strong>{{email.formattedCode}}</strong>.</p>
<p>The link and the code expire in {{email.expiresIn}}.</p>
{{> footer}}
```

`{{> footer}}` is a partial: `footer.html` from your location, or from who's if it had one. A
partial in a `.txt` template is `footer.txt`.

For each file, template or partial, who looks in this order, and the first that has it wins:

1. each location in `who.email.templates`, in order;
2. each library's location (below), in bean order;
3. who's own templates.

### What each email carries

| Record | Accessors |
|---|---|
| `EmailLinkMessage` | `address`, `link`, `code`, `formattedCode` (such as `K7F-2QM`), `expiresAt`, `validFor`, `expiresIn` (such as `15 minutes`) |
| `InvitationMessage` | the same, plus `roles`, the names of the roles the invitation gave |
| `EmailAddressConfirmationMessage` | the same as `EmailLinkMessage`, plus `change`: true when the address replaces another (`{{#email.change}}…{{/email.change}}`) |
| `EmailAddressChangedMessage` | `address` (the old address), `newAddress`, `maskedNewAddress` (such as `n***@example.com`) |
| `PasskeyAddedMessage` | `address`, `label`, the passkey's name |

Don't log a link or a code.

### Values of your own

To give a template something who doesn't know, such as the person's display name, register a
`WhoEmailModelContributor`:

```java
@Bean
WhoEmailModelContributor displayNames(Profiles profiles) {
    return email -> profiles.findByAddress(email.address())
        .map(p -> Map.<String, Object>of("displayName", p.displayName()))
        .orElse(Map.of());
}
```

Templates then use `{{extra.displayName}}`. Every contributor's map is merged, and a later bean's
key wins. Since a missing variable fails the render, give every email the keys your templates use.

### Templates from a library

A library built on who can ship its own versions of who's emails, and its own subjects, by
registering a `WhoEmailTemplatesContributor` bean, typically from its auto-configuration:

```java
@Bean
@Order(100)
WhoEmailTemplatesContributor acmeEmailTemplates() {
    return new WhoEmailTemplatesContributor("classpath:/com/acme/email/", "com/acme/email-messages");
}
```

The first argument is the library's template location, searched after the application's and before
who's. The second is the basename of a message bundle with the library's subjects, or `null`:
subjects are read from the application's messages, then from each library's bundle, then from
who's. Several libraries are searched in bean order (`@Order`). An application still replaces any one
of a library's files from its own location, as it replaces who's.

For a native image, the library registers its own with a `RuntimeHintsRegistrar`; who registers
only its own. Register the templates as resources and the subjects as a resource bundle:

```java
hints.resources().registerPattern("com/acme/email/*");
hints.resources().registerResourceBundle("com/acme/email-messages");
```

## Subjects

A subject is the message `who.email.<template>.subject`, read from your `MessageSource` first and
then from who's defaults:

| Key | Default |
|---|---|
| `who.email.sign-in.subject` | `Your sign-in link and code` |
| `who.email.invitation.subject` | `You have been invited` |
| `who.email.address-confirmation.subject` | `Confirm your email address` |
| `who.email.address-changed.subject` | `Your email address was changed` |
| `who.email.passkey-added.subject` | `A passkey was added to your account` |

Set one in `src/main/resources/messages.properties`, which Spring Boot loads for you:

```properties
who.email.sign-in.subject=Sign in to Acme
```

A subject is a Mustache template too, with the same `email`, `app` and `extra` as the body, so it
can name the app, which tells someone with accounts on two apps which one wrote:

```properties
who.email.model.name=Acme
who.email.sign-in.subject=Sign in to {{app.name}}
```

A rendered subject is one line: every run of line breaks and other control characters becomes one
space. who reads the message without arguments, so Spring does not run it through `MessageFormat`;
`{0}` and `'` are plain text. The exception is `spring.messages.always-use-message-format=true`:
Spring then runs every message of yours through `MessageFormat`, which breaks a subject of yours
with `{{`. who's and libraries' subjects are read separately and are not affected. A subject
that doesn't compile fails startup, like a template; a variable it names that the model lacks fails
the render, with an error naming the subject's key and locale.

## Languages

Each email is rendered in a locale: the request's (Spring's `LocaleContextHolder`, which by default
follows `Accept-Language`), or `who.email.locale` when there is no request, else the JVM's.
Sign-in links and address confirmations are sent off the request thread, and they keep the
request's locale.

Subjects follow the locale through your `MessageSource`, so `messages_fr.properties` gives French
subjects. Templates are not chosen by locale; to word an email by language, put the wording in your
messages and the language-specific parts in `extra`.

## Sending through something else

To deliver through a provider's API instead of SMTP, register a `WhoEmailSender` and leave
`who-email-smtp` out. It gets a rendered email:

```java
@Bean
WhoEmailSender whoEmailSender(SesV2Client ses, @Value("${who.email.from}") String from) {
    return email -> ses.sendEmail(request -> request
        .fromEmailAddress(from)
        .destination(d -> d.toAddresses(email.to()))
        .content(c -> c.simple(m -> m
            .subject(s -> s.data(email.subject()))
            .body(b -> {
                b.text(t -> t.data(email.text()));
                if (email.html() != null) {
                    b.html(h -> h.data(email.html()));
                }
            }))));
}
```

Throw if the email could not be delivered: who then undoes what the email promised, such as the
stored sign-in token, and reports it as not sent. `email.source()` is the record it was rendered
from, for a provider that renders its own templates: key them on `email.source().template()`.

To render differently, register your own `WhoEmailRenderer`; who's Mustache renderer then backs
off, and `who-email` is not needed.

## Never inside a transaction

`WhoEmails.send` throws `IllegalStateException` if a transaction is open, before anything is
rendered or sent. An email sent inside a transaction can promise something the transaction then
rolls back, and it holds a database connection while the mail server answers. who sends its own
emails after its transactions commit, so you only meet this if you call who from inside your own
transaction, such as `InvitationService.invite` from a `@Transactional` method.

## Native images

who's own templates and subjects are in its native-image metadata, along with the
`templates/who/email/` location. If `who.email.templates` points elsewhere, add a resource hint for
those files.
