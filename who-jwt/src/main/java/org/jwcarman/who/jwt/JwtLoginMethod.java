/*
 * Copyright © 2026 James Carman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jwcarman.who.jwt;

import static java.util.Objects.requireNonNull;

import java.util.Optional;
import java.util.function.Supplier;

import jakarta.servlet.http.HttpServletRequest;

import org.jwcarman.who.spring.security.StatelessLoginMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationConverter;
import org.springframework.security.web.authentication.AuthenticationConverter;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * JWT sign-in as a {@link StatelessLoginMethod}: Spring Security's resource server, verifying with
 * the application's {@link JwtDecoder} and signing in through {@link
 * WhoJwtAuthenticationConverter}. It matches the requests with an {@code Authorization: Bearer}
 * header. A request a session already signed in ignores the token, as an API key's does, so the
 * token neither replaces the session's sign-in nor exempts the cookie from CSRF.
 */
public final class JwtLoginMethod implements StatelessLoginMethod {

  private static final String BEARER = "bearer ";

  private final WhoJwtAuthenticationConverter converter;
  private final Supplier<JwtDecoder> decoder;

  /**
   * Creates the login method.
   *
   * @param converter signs a verified JWT in
   * @param decoder verifies JWTs
   */
  public JwtLoginMethod(WhoJwtAuthenticationConverter converter, JwtDecoder decoder) {
    this(converter, fixed(decoder));
  }

  /**
   * Creates the login method with a decoder looked up only when the chain is built, so a missing
   * decoder fails startup only where JWT sign-in is mounted.
   *
   * @param converter signs a verified JWT in
   * @param decoder supplies the decoder that verifies JWTs
   */
  public JwtLoginMethod(WhoJwtAuthenticationConverter converter, Supplier<JwtDecoder> decoder) {
    this.converter = requireNonNull(converter, "converter must not be null");
    this.decoder = requireNonNull(decoder, "decoder must not be null");
  }

  private static Supplier<JwtDecoder> fixed(JwtDecoder decoder) {
    requireNonNull(decoder, "decoder must not be null");
    return () -> decoder;
  }

  @Override
  public void configure(HttpSecurity http) {
    JwtDecoder jwtDecoder = decoder.get();
    // Name the bearer converter: left unset, the resource server takes any AuthenticationConverter
    // bean in the context, such as email link's, and runs it on every request. It also decides
    // which requests the resource server exempts from CSRF, so a request a session already signed
    // in is not a bearer request: the token is ignored, and the cookie still needs its CSRF token.
    http.oauth2ResourceServer(
        server ->
            server
                .authenticationConverter(
                    new UnlessSignedIn(new BearerTokenAuthenticationConverter()))
                .jwt(jwt -> jwt.decoder(jwtDecoder).jwtAuthenticationConverter(converter)));
  }

  /**
   * Matches a request with a bearer token that is not already signed in, such as by a session
   * cookie: a token beside a live session does not spare the cookie its CSRF token.
   *
   * @return the matcher
   */
  @Override
  public RequestMatcher carriesCredential() {
    return request -> hasBearerToken(request) && !StatelessLoginMethod.alreadySignedIn();
  }

  /**
   * Matches a request with a bearer token, signed in already or not: it is not a browser session.
   *
   * @return the matcher
   */
  @Override
  public RequestMatcher presentsCredential() {
    return JwtLoginMethod::hasBearerToken;
  }

  /** Converts a bearer token only on a request not already signed in, as API keys do. */
  private record UnlessSignedIn(AuthenticationConverter delegate)
      implements AuthenticationConverter {

    @Override
    public Authentication convert(HttpServletRequest request) {
      return StatelessLoginMethod.alreadySignedIn() ? null : delegate.convert(request);
    }
  }

  private static boolean hasBearerToken(HttpServletRequest request) {
    String authorization = request.getHeader("Authorization");
    return authorization != null
        && authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())
        && authorization.length() > BEARER.length()
        && !authorization.substring(BEARER.length()).isBlank();
  }

  /** Bearer tokens (RFC 6750). */
  @Override
  public Optional<String> challenge() {
    return Optional.of("Bearer");
  }

  @Override
  public String name() {
    return "jwt";
  }
}
