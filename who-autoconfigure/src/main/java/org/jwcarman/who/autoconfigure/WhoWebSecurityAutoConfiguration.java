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
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.util.ClassUtils;

/**
 * who's default {@link SecurityFilterChain}, in place of Spring Boot's: every sign-in method on the
 * classpath ({@link WhoSecurity#all()}), the {@linkplain WhoPublicPaths public paths} open to
 * signed-out requests, CSRF for every request that brings a session, Actuator's health endpoint
 * open as Boot's own chain leaves it, and every other request signed in. It backs off as soon as
 * the application defines a {@link SecurityFilterChain} of its own, and takes the place of the
 * default chains Spring Boot would register: form login, the resource server's, Actuator's and
 * {@code oauth2Login}'s.
 */
@AutoConfiguration(
    after = {
      WhoSpringSecurityAutoConfiguration.class,
      WhoJwtAutoConfiguration.class,
      WhoApiKeyAutoConfiguration.class,
      WhoEmailLinkAutoConfiguration.class,
      WhoPasskeyAutoConfiguration.class,
      WhoOidcAutoConfiguration.class
    },
    afterName =
        "org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration",
    beforeName = {
      "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration",
      "org.springframework.boot.security.oauth2.server.resource.autoconfigure.web.OAuth2ResourceServerWebSecurityAutoConfiguration",
      "org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration",
      "org.springframework.boot.security.oauth2.client.autoconfigure.servlet.OAuth2ClientWebSecurityAutoConfiguration"
    })
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(
    name = {
      "org.jwcarman.who.spring.security.WhoSecurity",
      "org.springframework.security.web.SecurityFilterChain",
      "org.springframework.security.config.annotation.web.builders.HttpSecurity"
    })
// Boot's @ConditionalOnDefaultServletWebSecurity in full: that annotation exists only from Spring
// Boot 4.1.2, and on 4.1.0 or 4.1.1 Spring would drop it unread, so this chain would not back off.
@ConditionalOnMissingBean(SecurityFilterChain.class)
public class WhoWebSecurityAutoConfiguration {

  /** A request that brings no session, so a forged copy of it would be signed out too. */
  private static final RequestMatcher NO_SESSION =
      request -> request.getRequestedSessionId() == null;

  private static final String ACTUATOR =
      "org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration";

  /**
   * The default chain.
   *
   * <p>Without a browser sign-in method who gives no request a session, so CSRF then ignores the
   * requests that bring none, and a signed-out client may post to a public path. A request that
   * brings a session, which something other than who may have started, keeps its CSRF check.
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
      http.csrf(csrf -> csrf.ignoringRequestMatchers(NO_SESSION));
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
