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
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.web.util.matcher.OrRequestMatcher;

/**
 * Mounts every who sign-in method in the application context into one chain:
 *
 * <ul>
 *   <li>when there is a {@link PasswordlessLoginMethod} bean, everything {@link
 *       WhoWebSecurity#passwordless()} does for browser sessions;
 *   <li>every {@link StatelessLoginMethod} bean, and, when the chain has CSRF protection, an
 *       exemption for the requests that one of their credentials signs in;
 *   <li>the {@link BrowserEndpoints} beans when there is a browser method, else the {@link
 *       StatelessEndpoints} beans;
 *   <li>a {@code 401} for a signed-out request and a {@code 403} for a denied one, handed to the
 *       application's exception handling. Without a browser method, the {@code 401} also names each
 *       stateless method's scheme in {@code WWW-Authenticate}, as {@link WhoSecurity#stateless()}
 *       does.
 * </ul>
 *
 * <p>It does not authorize requests: the chain's own {@code authorizeHttpRequests} does, with
 * {@link WhoPublicPaths} for the paths a signed-out request must reach. Obtain one with {@link
 * WhoSecurity#all()}.
 */
public class WhoSecurityConfigurer
    extends AbstractHttpConfigurer<WhoSecurityConfigurer, HttpSecurity> {

  private static final Logger log = LoggerFactory.getLogger(WhoSecurityConfigurer.class);

  static final String NO_METHODS =
      "WhoSecurity.all() found no sign-in methods; add who-email-link, who-passkey, who-apikey or"
          + " who-jwt to the application";

  WhoSecurityConfigurer() {}

  @Override
  public void init(HttpSecurity http) {
    ApplicationContext context = http.getSharedObject(ApplicationContext.class);
    boolean browser =
        context.getBeanProvider(PasswordlessLoginMethod.class).stream().findAny().isPresent();
    List<StatelessLoginMethod> stateless =
        context.getBeanProvider(StatelessLoginMethod.class).orderedStream().toList();
    if (!browser && stateless.isEmpty()) {
      log.warn(NO_METHODS);
    }

    if (browser) {
      http.with(new PasswordlessConfigurer(), Customizer.withDefaults());
    } else {
      StatelessAnswers.apply(http, context, stateless);
    }

    for (StatelessLoginMethod method : stateless) {
      method.configure(http);
    }
    if (!browser) {
      // a browser chain applies the BrowserEndpoints, which serve its stateless callers too
      WhoStatelessConfigurer.applyEndpoints(http, context);
    }
    WhoStatelessConfigurer.logMethods(log, stateless);
    // Only adjust CSRF the chain already has: http.csrf(...) would turn it back on in a chain that
    // turned it off.
    CsrfConfigurer<HttpSecurity> csrf = CsrfConfigurers.of(http);
    if (csrf != null && !stateless.isEmpty()) {
      csrf.ignoringRequestMatchers(
          new OrRequestMatcher(
              stateless.stream().map(StatelessLoginMethod::carriesCredential).toList()));
    }
  }
}
