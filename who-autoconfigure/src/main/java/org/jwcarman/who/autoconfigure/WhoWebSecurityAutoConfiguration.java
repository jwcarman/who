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
package org.jwcarman.who.autoconfigure;

import static org.springframework.security.config.Customizer.withDefaults;

import org.jwcarman.who.spring.security.PasswordlessLoginMethod;
import org.jwcarman.who.spring.security.WhoPublicPaths;
import org.jwcarman.who.spring.security.WhoSecurity;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.endpoint.web.WebServerNamespace;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.boot.security.autoconfigure.web.servlet.ConditionalOnDefaultServletWebSecurity;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.util.ClassUtils;

/**
 * who's default {@link SecurityFilterChain}, in place of Spring Boot's: every sign-in method on the
 * classpath ({@link WhoSecurity#all()}), the {@linkplain WhoPublicPaths public paths} open to
 * signed-out requests, CSRF only where a browser sign-in method gives requests a session,
 * Actuator's health endpoint open as Boot's own chain leaves it, and every other request signed in.
 * It backs off as soon as the application defines a {@link SecurityFilterChain} of its own.
 */
@AutoConfiguration(
    after = {
      WhoSpringSecurityAutoConfiguration.class,
      WhoJwtAutoConfiguration.class,
      WhoApiKeyAutoConfiguration.class,
      WhoEmailLinkAutoConfiguration.class,
      WhoPasskeyAutoConfiguration.class
    },
    afterName =
        "org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration",
    beforeName = {
      "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration",
      "org.springframework.boot.security.oauth2.server.resource.autoconfigure.web.OAuth2ResourceServerWebSecurityAutoConfiguration",
      "org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration"
    })
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = "org.jwcarman.who.spring.security.WhoSecurity")
@ConditionalOnDefaultServletWebSecurity
public class WhoWebSecurityAutoConfiguration {

  private static final String ACTUATOR =
      "org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration";

  /**
   * The default chain.
   *
   * <p>Without a browser sign-in method nothing gives a request a session, so there is no session
   * for CSRF to guard: CSRF then ignores every request, and a signed-out client may post to a
   * public path.
   *
   * @param http the chain builder
   * @param publicPaths the paths open to signed-out requests
   * @param browserMethods the browser sign-in methods
   * @return the chain
   */
  @Bean
  @Order(SecurityFilterProperties.BASIC_AUTH_ORDER)
  public SecurityFilterChain whoSecurityFilterChain(
      HttpSecurity http,
      WhoPublicPaths publicPaths,
      ObjectProvider<PasswordlessLoginMethod> browserMethods) {
    boolean actuator = ClassUtils.isPresent(ACTUATOR, getClass().getClassLoader());
    if (browserMethods.stream().findAny().isEmpty()) {
      // Ignore rather than disable: WhoSecurity.all() customizes CSRF when the chain is built, and
      // that would bring a disabled CSRF back.
      http.csrf(csrf -> csrf.ignoringRequestMatchers(AnyRequestMatcher.INSTANCE));
    }
    http.with(WhoSecurity.all(), withDefaults())
        .authorizeHttpRequests(
            requests -> {
              if (actuator) {
                Health.permit(requests);
              }
              requests.requestMatchers(publicPaths.patterns()).permitAll();
              requests.anyRequest().authenticated();
            });
    return http.build();
  }

  /** Actuator's health endpoint, loaded only when Actuator is on the classpath. */
  private static final class Health {

    private Health() {}

    static void permit(
        AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry
            requests) {
      requests
          .requestMatchers(
              EndpointRequest.to("health"),
              EndpointRequest.toAdditionalPaths(WebServerNamespace.SERVER, "health"))
          .permitAll();
    }
  }
}
