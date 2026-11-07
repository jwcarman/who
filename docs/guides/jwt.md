# JWT

## Point at your authorization server

Tell Who where your authorization server lives:

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          jwk-set-uri: https://your-auth-provider.com/.well-known/jwks.json
          # or: issuer-uri: https://your-auth-provider.com
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

## Wire the converter

Wire `WhoJwtAuthenticationConverter` into your resource server security filter chain:

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
