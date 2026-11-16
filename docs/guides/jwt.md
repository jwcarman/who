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
