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

import static java.util.Objects.requireNonNull;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.jwcarman.who.spring.security.web.NotSignedInException;
import org.jwcarman.who.spring.security.web.WhoExceptionHandoff;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.ExceptionTranslationFilter;

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
    // a recent-sign-in challenge goes only with a 401, whoever answers the refusal
    http.addFilterBefore(new StepUpChallengeFilter(), ExceptionTranslationFilter.class);
    http.exceptionHandling(
        e ->
            e.authenticationEntryPoint(entryPoint(handoff, methods))
                .accessDeniedHandler(deniedHandler(handoff)));
  }

  /**
   * The denied answer: {@code 403} through the handoff, except for a URL rule that needs a recent
   * sign-in, which answers with that rule's own refusal and challenge.
   */
  static AccessDeniedHandler deniedHandler(WhoExceptionHandoff handoff) {
    return (request, response, denied) -> {
      if (denied instanceof AuthorizationDeniedException authorization
          && authorization.getAuthorizationResult() instanceof RecentSignInDecision decision) {
        decision
            .challenge()
            .ifPresent(challenge -> StepUpChallenges.offer(request, response, challenge));
        // Spring Security never puts a granted result in a denial
        handoff.handOff(
            request,
            response,
            requireNonNull(decision.refusal(), "a denial is never granted"),
            HttpStatus.FORBIDDEN);
        return;
      }
      handoff.handOff(request, response, denied, HttpStatus.FORBIDDEN);
    };
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

  /** Whether two challenges name the same scheme: schemes are case-insensitive. */
  static boolean sameScheme(String present, String challenge) {
    return scheme(present).equalsIgnoreCase(scheme(challenge));
  }

  /** A challenge's scheme: its first token. */
  private static String scheme(String challenge) {
    String trimmed = challenge.strip();
    int end = trimmed.indexOf(' ');
    return end < 0 ? trimmed : trimmed.substring(0, end);
  }
}
