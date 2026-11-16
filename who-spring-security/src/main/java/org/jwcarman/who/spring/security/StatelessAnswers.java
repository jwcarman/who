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

import java.util.List;
import java.util.Optional;

import org.jwcarman.who.spring.security.web.NotSignedInException;
import org.jwcarman.who.spring.security.web.WhoExceptionHandoff;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;

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
    List<String> challenges =
        methods.stream().map(StatelessLoginMethod::challenge).flatMap(Optional::stream).toList();
    http.exceptionHandling(
        e ->
            e.authenticationEntryPoint(
                    (request, response, authException) -> {
                      // set before the handoff, so an application's own answer keeps it
                      challenges.forEach(c -> response.addHeader(HttpHeaders.WWW_AUTHENTICATE, c));
                      handoff.handOff(
                          request, response, new NotSignedInException(), HttpStatus.UNAUTHORIZED);
                    })
                .accessDeniedHandler(
                    (request, response, denied) ->
                        handoff.handOff(request, response, denied, HttpStatus.FORBIDDEN)));
  }
}
