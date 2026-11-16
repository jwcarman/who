# JWT

## Turn it on

Add the dependency (its version comes from who's BOM):

```xml
<dependency>
    <groupId>org.jwcarman.who</groupId>
    <artifactId>who-jwt</artifactId>
</dependency>
```

`who.jwt.enabled=false` turns JWT sign-in off without removing the jar.

## Point at your authorization server

Tell Who where your authorization server lives. When who mounts JWT sign-in (in its default chain
or through `WhoSecurity.all()`), startup fails until one of these is set or you register a
`JwtDecoder` bean:

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          jwk-set-uri: https://your-auth-provider.com/.well-known/jwks.json
          # or: issuer-uri: https://your-auth-provider.com
          audiences: https://api.example.com   # required; see below
```

## Validate the issuer

With `issuer-uri`, Spring Security checks the `iss` claim for you. With `jwk-set-uri`, nothing checks it, so add a `JwtIssuerValidator` to your `JwtDecoder`. A `JwtDecoder` bean replaces the `jwk-set-uri` property, so keep the URL in one place:

```java
@Bean
JwtDecoder jwtDecoder() {
    NimbusJwtDecoder decoder = NimbusJwtDecoder
        .withJwkSetUri("https://your-auth-provider.com/.well-known/jwks.json")
        .build();
    decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("https://your-auth-provider.com"));
    return decoder;
}
```

A token with no `iss` or no `sub` claim gets 401.

## Validate the audience

Spring's resource server does not check a token's `aud` claim unless told to. Without that check,
any token your authorization server issued for any other application would sign in here, and
since [social login](social-login.md) shares the `(issuer, subject)` credential, so would an ID
token a provider issued to another site for the same person. When Spring Boot builds the decoder
from `issuer-uri`, `jwk-set-uri` or `public-key-location`, who therefore refuses to mount JWT
sign-in, and startup fails, until the audience is set:

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://your-auth-provider.com
          audiences: https://api.example.com
```

A `JwtDecoder` bean of your own owns the check: add a validator for `aud` to it, beside the issuer
validator above.

## Wire the converter

With no `SecurityFilterChain` of your own there is nothing to wire: who's default chain is a
resource server that verifies tokens with your `JwtDecoder` and signs them in with
`WhoJwtAuthenticationConverter` (see [Getting Started](getting-started.md#secure-the-app)). A
request with an `Authorization: Bearer` header needs no CSRF token and never gets a session. In a
chain of your own, `WhoSecurity.all()` does the same alongside every other sign-in method.

An API with a chain of its own, beside a browser chain, mounts JWTs (and API keys, when
`who-apikey` is on) with `WhoSecurity.stateless()`: no session, no CSRF, and a `401` with
`WWW-Authenticate: Bearer` for a call without a token (see [Getting
Started](getting-started.md#a-website-and-an-api)):

```java
http.securityMatcher("/api/**")
    .with(WhoSecurity.stateless(), Customizer.withDefaults())
    .authorizeHttpRequests(a -> a.anyRequest().authenticated());
```

To write the resource server yourself, wire `WhoJwtAuthenticationConverter` into it:

```java
@Bean
@Order(2)
public SecurityFilterChain apiSecurityFilterChain(
        HttpSecurity http,
        WhoJwtAuthenticationConverter whoJwtAuthenticationConverter) throws Exception {
    http
        .securityMatcher("/api/**")
        .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(csrf -> csrf.disable())
        .oauth2ResourceServer(rs -> rs
            .jwt(jwt -> jwt.jwtAuthenticationConverter(whoJwtAuthenticationConverter))
        );
    return http.build();
}
```

`WhoJwtAuthenticationConverter` is auto-configured by `who-autoconfigure` — inject it as a bean.

## Link a token to an identity

Who does not create identities from tokens. Link an issuer/subject pair to an existing identity with `FederatedCredentialService`, which saves the credential, links it, and publishes a `CredentialAdded` event labelled with the issuer:

```java
FederatedCredential credential = federatedCredentialService.link(identity, "https://your-auth-provider.com", "user-123");

// later
federatedCredentialService.unlink(credential.id());   // publishes CredentialRevoked
```

`FederatedCredentialService` comes from `who-federated`, which `who-jwt` and `who-oidc` both bring, and is auto-configured by `who-autoconfigure`. A provider account linked by [social login](social-login.md) is the same credential, so a token with the same issuer and subject reaches the same identity. Each request with a token Who refuses publishes `SignInFailed` (method `JWT`; reasons `MISSING_CLAIMS`, `UNKNOWN_CREDENTIAL`, `INACTIVE_IDENTITY`). A token that signs in publishes no who event, since it authenticates every request. The token itself is never published or logged. A token Spring rejects before Who sees it (bad signature, expired) publishes nothing.

## Recent sign-in

An action marked [`@RequiresRecentSignIn`](security-model.md#recent-sign-in) asks when the token's person signed in. who reads the token's `auth_time` claim (OpenID Connect Core, RFC 9470): the time the person last signed in at the authorization server, which stays the same when the token is refreshed.

- `auth_time` within `who.security.recent-sign-in` (10 minutes by default): allowed.
- `auth_time` older: `401`, with the RFC 9470 step-up challenge:

    ```text
    WWW-Authenticate: Bearer error="insufficient_user_authentication", error_description="A more recent sign-in is required", max_age=600
    ```

    The client has the person sign in again, sending `max_age` (the seconds from the challenge) on its authorization request, so the server asks for the password if the sign-in is older than that, and retries with the new token.

    On who's chains the challenge goes only with a `401`, whoever writes it: who, your advice, or Spring MVC. If your advice answers the refusal with another status, or your code catches it and carries on, the response gets no challenge to contradict it. On a chain you build without who's configurers, who sets the header as it refuses.
- No `auth_time`: `403`. who cannot tell when the person signed in, and signing in again would bring another token without it.

An `auth_time` a little in the future, from clock skew, counts as now. Tokens without `auth_time` work as before everywhere else; only marked actions refuse them.

Whether access tokens carry `auth_time` depends on the server:

| Server | `auth_time` in access tokens |
|---|---|
| Keycloak | yes, and kept on refresh |
| Okta | yes, for tokens a person signed in for (not client credentials) |
| Amazon Cognito | yes |
| Microsoft Entra ID | only as an optional claim you configure; Entra's own step-up uses claims challenges, which who does not send |
| Auth0 | no |
| Google | its access tokens are not JWTs |

Spring Security's own `JwtAuthenticationConverter` stamps its bearer factor with the time of the request, which says a token was presented, not that a person signed in. `WhoJwtAuthenticationConverter` stamps it with `auth_time`, or not at all.
