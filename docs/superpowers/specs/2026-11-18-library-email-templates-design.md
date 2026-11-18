# Library email templates and subjects with values

## Why

roux, an application built on who, wants to ship branded text and HTML templates for all five of
who's emails: they name the app and use its brand colour. roux is a library that other applications
(and roux's own deployments) put on the classpath. Two things stop it today:

1. **No place for a library's templates.** who looks in the application's location
   (`who.email.templates`, default `classpath:/templates/who/email/`) and then in its own built-ins.
   A library that puts files in the application's location collides with the application's own
   files, and which one wins depends on classpath order.
2. **Subjects cannot name the app.** A subject is a plain message, `who.email.<template>.subject`.
   Someone with accounts on two apps built on who gets "Your sign-in link and code" from both and
   cannot tell which one sent it.

Neither blocks roux; its emails wait for both.

## Goals

- `who.email.templates` becomes a list of Spring resource locations, so an application can search
  several (such as a `file:` location while editing) in an order it chooses.
- A library contributes a template location that who searches after the application's and before
  its own, per file: the application's locations in list order, then each library's in bean order,
  then who's.
- A library contributes its own subjects (and other email messages) the same way: the application's
  messages, then each library's, then who's.
- Subjects are Mustache templates rendered with the body's model, so
  `who.email.sign-in.subject=Sign in to {{app.name}}` works.
- An existing subject with no `{{` renders exactly as before.

## Non-goals

- Choosing templates by locale. Wording by language still goes through messages.
- Changing what `app` or `extra` hold, or how contributors merge.
- HTML in subjects. A subject is plain text.

## Anti-goals

- A subject that can carry a line break into the mail header.
- Making which template wins depend on classpath order again, between the application, a library
  and who.

## Design

### The search order

For each file (a template or a partial), the first location that has it wins:

1. each location in `who.email.templates`, in list order (default:
   `classpath:/templates/who/email/`);
2. each `WhoEmailTemplates` bean's location, in bean order (`@Order` / `Ordered`);
3. who's built-ins (`BUILT_IN_LOCATION`).

Every location is a Spring resource location (`classpath:`, `file:` and so on), resolved by the
`ResourcePatternResolver`. An application overrides a library's template file by file, from its own
location, as it overrides who's. An application that defines its own `WhoEmailRenderer` bean
replaces all of this, as today.

### The application's locations: `who.email.templates` as a list

`WhoProperties.Email.templates` becomes a `List<String>`, default
`[classpath:/templates/who/email/]`. Spring binds a comma-separated value
(`who.email.templates=classpath:/templates/who/email/,file:./email/`) and an indexed list alike, so
an existing single value keeps working. A location without a trailing `/` gets one.

### A library's templates: `WhoEmailTemplates`

A new public record in `who-email`, beside `WhoEmailMessages`:

```java
public record WhoEmailTemplates(String location, @Nullable String messages) {}
```

- `location`: where the library's templates are, such as `classpath:/com/acme/roux/email/`. A
  missing trailing `/` is added, as for `who.email.templates`.
- `messages`: an optional message bundle basename, such as `com/acme/roux/email-messages`, holding
  the library's subjects.

A library registers one as a bean, typically from its own auto-configuration. Several are searched
in bean order (`@Order` / `Ordered`).

`MustacheWhoEmailRenderer` takes an ordered list of locations instead of one: the application's
locations, then each `WhoEmailTemplates.location()` in bean order; it adds `BUILT_IN_LOCATION`
last itself. `find` already
walks a list, so lookup, partials (`{{> footer}}` found the same way, with the including template's
extension) and the startup compile of every template cover the new locations unchanged. A library
location that does not exist is skipped, as the application's is today.

The constructor changes from `(resources, String templateLocation, messages, app, contributors)`
to `(resources, List<String> templateLocations, messages, app, contributors)`, searched in the
given order. who's built-ins stay implicit and last. Breaking for anyone who builds the renderer
by hand; that is fine (owner and one friend use who).

### A library's subjects

`AppFirstMessageSource` already answers from the application's `MessageSource` first, then from a
`ResourceBundleMessageSource` of who's bundles (`WhoEmailMessages`). The auto-configuration builds
that bundle source from the libraries' `messages` basenames first, in bean order, then who's own
`WhoEmailMessages` basenames. `ResourceBundleMessageSource` answers from the first basename that has
the code, so the order is application, libraries, who.

who's own `WhoEmailMessages` stays as it is: it is who's features' way of adding their bundles, not
a library's.

### Subjects with values

`render` resolves the message as today, then compiles it with the text compiler (no HTML escaping)
and executes it with the same model as the body (`email`, `app`, `extra`). A variable the model
lacks fails the render, as in a body.

Compiled subjects are cached by their message text, since the text differs by locale.

After rendering, every run of control characters and line breaks (CR, LF, U+2028, U+2029, other ISO
controls) becomes one space, and the result is trimmed. A subject is one line whatever a value holds.
Collapsing, rather than refusing, means a value from a user (none today, but `extra` is the
application's) cannot stop an email from being sent.

At startup, the renderer resolves each built-in and library template's subject in the default
locale, when one exists, and compiles it, so a subject with broken Mustache fails startup rather
than the first send, as templates do. Subjects only in other locales are compiled on first use.

Spring's `MessageSource` does not run `MessageFormat` when it is given no arguments, which is how who
asks, so `{` and `'` in a subject keep their meaning for Mustache. The email guide says so.

### Native images

who's own templates and bundles are in its native metadata already. A library's location and bundle
are the library's to register (`RuntimeHints` resources), as the application's own templates are
today. The guide says so.

## Approaches considered

1. **`who.email.templates` as a list and a `WhoEmailTemplates` bean per library (chosen).** The
   application orders its own locations; libraries add themselves with auto-configuration, so
   adding roux's jar is enough. Bean order settles two libraries.
2. **`who.email.templates` as a list alone.** Every application on roux would have to name roux's
   location, and roux's subjects would still need their own mechanism.
3. **One `classpath*:` location every jar writes into.** Today's problem: the application's and a
   library's files of the same name are ordered by the classpath.
4. **Subjects through `MessageFormat` arguments** (`Sign in to {0}`). Fixed positional arguments
   who would have to choose for every email; no access to `extra`, and a second template language
   beside Mustache.

## Docs

- `docs/guides/email.md`: a "Templates from a library" section (the bean, the order, native hints),
  and "Subjects" gains values, the one-line rule, and the `MessageFormat` note.
- `docs/guides/upgrading.md`: the renderer's constructor change; `who.email.templates` is a list
  (a single value still binds).
- CHANGELOG under `[Unreleased]`: Added (`who.email.templates` as a list, library templates,
  subjects with values), Breaking changes (the renderer's constructor; `WhoProperties.Email`'s
  `getTemplates()` returns a list).

## Left to the builder

- The exact names of tests and private helpers.
- Whether the subject cache is a `ConcurrentHashMap` keyed by text, like the template cache.
