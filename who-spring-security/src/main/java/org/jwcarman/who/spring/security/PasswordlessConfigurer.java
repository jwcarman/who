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

import static org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER;

import java.util.List;

import org.jwcarman.who.spring.security.web.NotSignedInException;
import org.jwcarman.who.spring.security.web.WhoExceptionHandoff;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;

/**
 * Makes a browser chain safe for session sign-in, using only Spring Security's own types: Spring's
 * SPA CSRF setup ({@link CsrfConfigurer#spa()}), {@code 401} instead of login redirects, a logout
 * that answers {@code 204} and reports {@code SignedOut}, no saved requests, a {@code no-referrer}
 * policy, and who's session-backed {@link WhoSessionSecurityContextRepository}. Every {@link
 * PasswordlessLoginMethod} bean is then applied in {@code @Order} order.
 *
 * <p>Obtain one with {@link WhoWebSecurity#passwordless()}.
 */
public class PasswordlessConfigurer
    extends AbstractHttpConfigurer<PasswordlessConfigurer, HttpSecurity> {

  private static final Logger log = LoggerFactory.getLogger(PasswordlessConfigurer.class);

  static final String NO_METHODS =
      "WhoWebSecurity.passwordless() found no sign-in methods; add who-email-link or who-passkey"
          + " to the application";
  static final String CSRF_REQUIRED =
      "WhoWebSecurity.passwordless() requires CSRF protection; remove csrf.disable() from this chain";
  static final String SESSIONS_REQUIRED =
      "WhoWebSecurity.passwordless() needs sessions; this chain is STATELESS";
  static final String CSRF_COOKIE = "XSRF-TOKEN";

  private boolean statelessChain;

  PasswordlessConfigurer() {}

  @Override
  public void init(HttpSecurity http) {
    if (CsrfConfigurers.of(http) == null) {
      throw new IllegalStateException(CSRF_REQUIRED);
    }
    // SessionManagementConfigurer.init (which runs before ours on Boot's HttpSecurity) shares a
    // request-attribute-only repository when the chain is STATELESS; Spring exposes the policy no
    // other way. Capture that before our repository replaces it.
    statelessChain =
        http.getSharedObject(SecurityContextRepository.class)
            instanceof RequestAttributeSecurityContextRepository;
    ApplicationContext context = http.getSharedObject(ApplicationContext.class);
    WhoSessionSecurityContextRepository repository =
        context.getBean(WhoSessionSecurityContextRepository.class);
    WhoExceptionHandoff handoff =
        context
            .getBeanProvider(WhoExceptionHandoff.class)
            .getIfAvailable(() -> new WhoExceptionHandoff(null));
    // a chain built without Spring Boot has no WhoPaths bean: who's endpoints stay under /who
    WhoPaths paths = context.getBeanProvider(WhoPaths.class).getIfAvailable(WhoPaths::defaults);

    // Spring's SPA setup: script-readable XSRF-TOKEN cookie, token loaded on every request,
    // BREACH-safe rendered tokens and plain header values accepted
    // a bearer token on a mixed chain may be refused for an old sign-in: its challenge goes only
    // with a 401, whoever answers the refusal
    http.addFilterBefore(new StepUpChallengeFilter(), ExceptionTranslationFilter.class);
    http.csrf(CsrfConfigurer::spa)
        .securityContext(c -> c.securityContextRepository(repository))
        // a saved request would only copy headers and cookies into the session
        .requestCache(c -> c.requestCache(new NullRequestCache()))
        // who formats no errors: a signed-out request and a denied one are handed to the
        // application's exception handling, so its own advice answers them
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (request, response, authException) ->
                            handoff.handOff(
                                request,
                                response,
                                new NotSignedInException(),
                                HttpStatus.UNAUTHORIZED))
                    .accessDeniedHandler(StatelessAnswers.deniedHandler(handoff)))
        .logout(
            l -> {
              l.logoutUrl(paths.of("/logout"))
                  .addLogoutHandler(
                      (request, response, authentication) ->
                          repository.onLogout(request, authentication))
                  .logoutSuccessHandler(
                      new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT))
                  .deleteCookies(CSRF_COOKIE);
              // every browser chain lets its signed-in person see and end their sessions
              context
                  .getBeanProvider(SessionEndpoints.class)
                  .ifAvailable(e -> e.configure(http, l, paths));
            })
        .headers(h -> h.referrerPolicy(r -> r.policy(NO_REFERRER)));

    List<PasswordlessLoginMethod> methods =
        context.getBeanProvider(PasswordlessLoginMethod.class).orderedStream().toList();
    if (methods.isEmpty()) {
      log.warn(NO_METHODS);
    }
    for (PasswordlessLoginMethod method : methods) {
      method.configure(http);
    }
  }

  @Override
  public void configure(HttpSecurity http) {
    if (statelessChain) {
      throw new IllegalStateException(SESSIONS_REQUIRED);
    }
  }
}
