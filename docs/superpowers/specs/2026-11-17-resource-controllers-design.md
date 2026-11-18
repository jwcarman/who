# who's resource endpoints become controllers

Status: draft for the owner. Date: 2026-11-17. Target: 0.23.0, on top of the email-templates work.

## Intent

who's resource endpoints (a signed-in person's sessions, passkeys, addresses, linked accounts and
API keys, and the admin endpoints) are servlet filters. Each one routes requests by hand, reads
bodies as JSON trees and writes its answers with `WhoJson`. That has two costs:

- **Native images break.** Spring registers a type for native reflection when a controller method
  returns it or takes it as a body. The filters return nothing, so no answer type is registered, and
  in a native image `GET /who/me` and the listings answer 500 ("Record components not available").
  This has been so since 0.21.0.
- **They are not what a Spring developer expects.** Hand routing, hand JSON, and an exception
  hand-off, where a controller would get all three from Spring MVC, along with validation,
  OpenAPI and the application's authorization rules.

Nothing requires them to be filters (owner and builder checked):

| Possible reason | Holds? |
|---|---|
| Paths under a configurable root | No. `@GetMapping("${who.base-path:/who}/me/api-keys")`, as `WhoMeController` does. |
| Answering before authorization | No. who's entry point already answers a signed-out request with `NotSignedInException` through the application's advice, as `/who/me` shows. |
| Different per chain | No. Each filter checks the caller itself (browser session, bearer token, API key); a controller does the same with `WhoCaller`. |
| The chain's logout handlers | Only for ending one's own session; see "Ending your own session". |

## Goals

- **Every resource endpoint is a `@RestController` method that returns its answer type
  directly** (owner's rule): a record, a `List` of records, or `void` with
  `@ResponseStatus(NO_CONTENT)`. A `201` is `@ResponseStatus(CREATED)`. No `ResponseEntity`, no
  `void` method that writes the body itself.
- **Request bodies are typed** (`@RequestBody` records), so they are registered too.
- **The wire shape is pinned on the types** against the application's `spring.jackson` settings:
  `@JsonNaming(LowerCamelCaseStrategy.class)` and `@JsonInclude(ALWAYS)` on every request and
  answer record, `@JsonFormat(shape = STRING)` on every `Instant`. The JSON is unchanged from
  0.22.
- **`WhoJson` and `AdminJson` are deleted.**
- **Native images work**: the native tests pass on Oracle GraalVM (CI) and Liberica NIK.

## Non-goals

- Changing any path, status, body or `Who-Error` code. Each endpoint's contract stays as its
  filter's Javadoc and tests describe it.
- The sign-in machinery. Spring Security's one-time-token, WebAuthn, OAuth2 and logout filters
  stay filters, and so do who's filters that run or complete a sign-in: `/who/sign-in` (it lists the
  methods of the chain that serves it), pending provider sign-up and link
  (`/who/oidc/pending/**`), and starting a link to a provider account. Their JSON types are
  registered with `@RegisterReflectionForBinding` on the bean that adds the filter.
- A per-chain set of resource endpoints. A controller answers on every chain that reaches it; it
  refuses callers it does not serve, as the filter did.

## Anti-goals

- A wire-format change, however small, that the TypeScript client or an app would notice.
- An answer type that is not visible in a controller method's signature.
- A resource endpoint that ignores the application's authorization rules.

## The design

### Endpoints that move

| Module | Today | Becomes |
|---|---|---|
| who-spring-security | `WhoMeController` (writes `WhoMeResponse` itself), `SessionsFilter` | `WhoMeController` returning `WhoMeResponse`; `SessionsController` |
| who-email-link | `EmailAddressFilter` | `EmailAddressesController` |
| who-passkey | `PasskeyCredentialsFilter` (listing and removing; registering stays Spring's filter) | `PasskeysController` |
| who-oidc | `LinkedAccountsFilter` (listing and unlinking) | `LinkedAccountsController` |
| who-apikey | `ApiKeysFilter` | `ApiKeysController` |
| who-admin | `IdentitiesFilter`, `InvitationsFilter` | `IdentitiesController`, `RolesController`, `InvitationsController` |

Each `*Endpoints` bean that today adds a filter to a chain is removed, or keeps only what is still
a filter. A controller is a bean of its module's auto-configuration, on when the module is.

### Paths

A constant `WhoPaths.ROOT = "${who.base-path:/who}"` gives every mapping its root:
`@GetMapping(WhoPaths.ROOT + "/me/api-keys")`. The `WhoPaths` bean still validates the root at
startup.

### Who may call

Each controller keeps its filter's rules, checked at the start of the method through `WhoCaller`
(browser session only, no API key, a bearer token allowed, and so on) and `@RequiresRecentSignIn`
where the filter checked the recent sign-in window. Signed out is `NotSignedInException`, and the
admin endpoints' permissions stay `@RequiresPermission`-style checks with the same codes.

**Behaviour change:** the application's `authorizeHttpRequests` now applies to these endpoints.
With who's default chain and the documented `WhoPublicPaths` + `anyRequest().authenticated()`, a
signed-out caller gets the same `401` through who's entry point. An application whose own rules
refuse `/who/**` now refuses these endpoints too, as it would any controller.

### Errors

Controllers throw who's exceptions and let Spring MVC resolve them: the application's advice, else
`@ResponseStatus`. `WhoExceptionHandoff` stays for the filters that remain. Any status that the
hand-off supplied which an exception does not declare (such as `409` for
`LastCredentialException`) moves onto the exception as `@ResponseStatus`, so the status does not
change.

### Ending your own session

`DELETE /who/me/sessions/{id}` on the caller's own session, and `?others=true`'s handling of it,
sign the browser out through the chain's logout handlers (who's, the application's, CSRF's, cookie
clearing, `LogoutSuccessEvent`). A controller cannot see a chain's handlers. **Builder's call:**
`WhoWebSecurity.passwordless()` adds a small filter that sets the chain's composite logout handler
as a request attribute, and `SessionsController` signs out with it. Without the attribute (a chain
that is not who's browser chain) the session cannot be the caller's own, since only a who browser
session may call.

### JSON

Records gain the annotations above. Request records replace the tree reads; a field the filter
treated as optional stays nullable, and a body the filter refused stays `400` with the same
`Who-Error`, through Bean Validation or the controller's own check, whichever matches the filter.

## Constraints

- Java 25, Spring Boot 4. No star imports, no warning suppression, no fully qualified names.
- The existing endpoint tests are the contract. They move from filter tests to MockMvc tests of
  the controllers and keep their assertions on status, body and `Who-Error`.
- One new test runs a who app with `spring.jackson.property-naming-strategy=SNAKE_CASE`,
  `spring.jackson.serialization.write-dates-as-timestamps=true` and
  `spring.jackson.default-property-inclusion=non_null`, and asserts every answer's shape is
  unchanged.
- The native tests must pass for all five native apps.

## Breaking changes (CHANGELOG and upgrading.md)

- `WhoJson`, `AdminJson` and the resource `*Endpoints` beans are removed.
- The application's authorization rules apply to who's resource endpoints.
- An application that applied a `*Endpoints` bean to a chain of its own (if that was public API)
  drops that call; the controllers are on with their module.

## Left to the builder

- Whether a module's endpoints are one controller or several.
- The request records' names.
- How the remaining sign-in filters' types are registered, if `@RegisterReflectionForBinding` on
  their bean is not enough.
