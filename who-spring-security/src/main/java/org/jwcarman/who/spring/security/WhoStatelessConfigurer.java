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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.session.DisableEncodeUrlFilter;

/**
 * Mounts who's stateless sign-in methods, every {@link StatelessLoginMethod} bean (API keys, bearer
 * JWTs), into a chain that never reads or creates a session:
 *
 * <ul>
 *   <li>the security context lives in the request only, and the request's session is hidden from
 *       the whole chain, so a session cookie that another chain made signs nothing in here, and is
 *       neither given a new id nor ended. Application code on this chain cannot use a session
 *       either: asking for one ({@code HttpSession} parameters, {@code @SessionAttributes},
 *       session-scoped beans, flash attributes) fails with {@code IllegalStateException};
 *   <li>logout is not mounted: there is no session to end;
 *   <li>CSRF protection is off, because nothing on this chain rides along with a request by itself:
 *       a forged cross-site request cannot attach the header a credential travels in. That holds
 *       only while the application adds no credential a browser sends by itself to this chain, such
 *       as {@code httpBasic()} or client certificates. An application's own {@code csrf(...)}
 *       settings for this chain are replaced;
 *   <li>a signed-out request gets {@code 401}, with a {@code WWW-Authenticate} header for each
 *       method that has a scheme ({@link StatelessLoginMethod#challenge()}), and a denied one
 *       {@code 403}, both handed to the application's exception handling.
 * </ul>
 *
 * <p>It mounts no browser sign-in method, even when one is on the classpath, so an application can
 * give its API a chain of its own beside a browser chain. With no stateless method in the
 * application, building the chain fails; so does a chain whose sessions are turned back on after
 * this configurer, with {@code sessionManagement(...)} or {@code securityContext(...)}, since it
 * would then sign requests in from a session with CSRF off. Obtain one with {@link
 * WhoSecurity#stateless()}.
 */
public final class WhoStatelessConfigurer
    extends AbstractHttpConfigurer<WhoStatelessConfigurer, HttpSecurity> {

  private static final Logger log = LoggerFactory.getLogger(WhoStatelessConfigurer.class);

  /** The startup line naming the stateless methods a chain accepts. */
  static final String METHODS_LINE = "who: this chain accepts {}";

  /** The startup failure when there is nothing to mount. */
  public static final String NO_METHODS =
      "WhoSecurity.stateless() found no sign-in methods; add who-jwt or who-apikey to the"
          + " application";

  /**
   * The startup failure when the chain has sessions after all: something set a session policy or a
   * security-context store after this configurer, which would sign requests in from a session on a
   * chain without CSRF protection.
   */
  public static final String STATELESS_REQUIRED =
      "WhoSecurity.stateless() needs a STATELESS chain; remove the sessionManagement(...) or"
          + " securityContext(...) that turns sessions back on";

  WhoStatelessConfigurer() {}

  /**
   * Makes the chain stateless as soon as this configurer is added, before any configurer is
   * initialized: Spring's session management picks the chain's context store when it is
   * initialized, and would pick the session if the policy came any later.
   */
  @Override
  public void setBuilder(HttpSecurity http) {
    super.setBuilder(http);
    http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
  }

  @Override
  public void init(HttpSecurity http) {
    ApplicationContext context = http.getSharedObject(ApplicationContext.class);
    List<StatelessLoginMethod> methods =
        context.getBeanProvider(StatelessLoginMethod.class).orderedStream().toList();
    if (methods.isEmpty()) {
      throw new IllegalStateException(NO_METHODS);
    }
    for (StatelessLoginMethod method : methods) {
      method.configure(http);
    }
    logMethods(log, methods);
    // The one place who turns CSRF off: every credential this chain accepts travels in a header a
    // browser never adds by itself, and the chain ignores the session cookie that it would add.
    http.csrf(csrf -> csrf.disable());
    http.logout(logout -> logout.disable());
    http.addFilterBefore(new SessionlessRequestFilter(), DisableEncodeUrlFilter.class);
    StatelessAnswers.apply(http, context, methods);
  }

  /**
   * Refuses a chain whose sessions were turned back on after this configurer: Spring's session
   * management shares a request-only store with the chain only when it is stateless.
   */
  @Override
  public void configure(HttpSecurity http) {
    if (!(http.getSharedObject(SecurityContextRepository.class)
        instanceof RequestAttributeSecurityContextRepository)) {
      throw new IllegalStateException(STATELESS_REQUIRED);
    }
  }

  /** Logs {@link #METHODS_LINE} for the methods, when there are any. */
  static void logMethods(Logger logger, List<StatelessLoginMethod> methods) {
    if (!methods.isEmpty() && logger.isInfoEnabled()) {
      logger.info(
          METHODS_LINE,
          String.join(", ", methods.stream().map(StatelessLoginMethod::name).toList()));
    }
  }
}
