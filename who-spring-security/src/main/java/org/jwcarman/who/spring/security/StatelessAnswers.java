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
package org.jwcarman.who.spring.security;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.jwcarman.who.spring.security.web.NotSignedInException;
import org.jwcarman.who.spring.security.web.WhoExceptionHandoff;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * How a chain with no browser sign-in answers: {@code 401} for a signed-out request, naming each
 * method's {@code WWW-Authenticate} scheme, and {@code 403} for a denied one, both handed to the
 * application's exception handling through {@link WhoExceptionHandoff}.
 */
final class StatelessAnswers {

  private StatelessAnswers() {}

  static void apply(
      HttpSecurity http, ApplicationContext context, List<StatelessLoginMethod> methods) {
    WhoExceptionHandoff handoff =
        context
            .getBeanProvider(WhoExceptionHandoff.class)
            .getIfAvailable(() -> new WhoExceptionHandoff(null));
    http.exceptionHandling(
        e ->
            e.authenticationEntryPoint(entryPoint(handoff, methods))
                .accessDeniedHandler(
                    (request, response, denied) ->
                        handoff.handOff(request, response, denied, HttpStatus.FORBIDDEN)));
  }

  /**
   * The signed-out answer: a {@code WWW-Authenticate} header for each method's scheme, then {@code
   * 401} through the handoff.
   */
  static AuthenticationEntryPoint entryPoint(
      WhoExceptionHandoff handoff, List<StatelessLoginMethod> methods) {
    List<String> challenges =
        methods.stream()
            .map(StatelessLoginMethod::challenge)
            .flatMap(Optional::stream)
            .distinct()
            .toList();
    return (request, response, authException) -> {
      // set before the handoff, so an application's own answer keeps it; each scheme once,
      // even when an error page comes back through here
      Collection<String> present = response.getHeaders(HttpHeaders.WWW_AUTHENTICATE);
      challenges.stream()
          .filter(c -> present.stream().noneMatch(p -> sameScheme(p, c)))
          .forEach(c -> response.addHeader(HttpHeaders.WWW_AUTHENTICATE, c));
      handoff.handOff(request, response, new NotSignedInException(), HttpStatus.UNAUTHORIZED);
    };
  }

  /** Whether a challenge already sent names the scheme: schemes are case-insensitive. */
  private static boolean sameScheme(String present, String scheme) {
    int end = present.indexOf(' ');
    String presentScheme = end < 0 ? present : present.substring(0, end);
    return presentScheme.equalsIgnoreCase(scheme);
  }
}
