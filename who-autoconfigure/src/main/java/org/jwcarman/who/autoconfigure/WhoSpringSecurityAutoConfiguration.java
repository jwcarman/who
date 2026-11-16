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

import java.time.Clock;

import org.jwcarman.who.core.event.WhoEventPublisher;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.spring.security.PasswordlessLoginMethod;
import org.jwcarman.who.spring.security.WhoPublicPaths;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.jwcarman.who.spring.security.web.LastSignInCookie;
import org.jwcarman.who.spring.security.web.WhoExceptionHandoff;
import org.jwcarman.who.spring.security.web.WhoMeController;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Wires who into Spring Security when {@code who-spring-security} is on the classpath: the session
 * repository, {@code GET /who/me}, the exception hand-off, and method security so that
 * {@code @RequiresPermission} is enforced (turn it off with {@code
 * who.method-security.enabled=false}).
 */
@AutoConfiguration(after = WhoAutoConfiguration.class)
@EnableConfigurationProperties(WhoProperties.class)
@ConditionalOnClass(name = "org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository")
public class WhoSpringSecurityAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public WhoSessionSecurityContextRepository whoSessionSecurityContextRepository(
      WhoService whoService, Clock clock, WhoEventPublisher whoEventPublisher) {
    return new WhoSessionSecurityContextRepository(whoService, clock, whoEventPublisher);
  }

  /**
   * Remembers in the browser which way someone last signed in. Turn it off with {@code
   * who.security.last-sign-in-cookie.enabled=false}.
   *
   * @return the cookie writer
   */
  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBooleanProperty(
      name = "who.security.last-sign-in-cookie.enabled",
      matchIfMissing = true)
  public LastSignInCookie lastSignInCookie() {
    return new LastSignInCookie();
  }

  /**
   * The paths a signed-out request may reach: the error page, each sign-in method's endpoints and
   * {@code who.security.public-paths}.
   *
   * @param methods the browser sign-in methods
   * @param properties the application's own public paths
   * @return the paths
   */
  @Bean
  @ConditionalOnMissingBean
  public WhoPublicPaths whoPublicPaths(
      ObjectProvider<PasswordlessLoginMethod> methods, WhoProperties properties) {
    return new WhoPublicPaths(
        methods.orderedStream().toList(), properties.getSecurity().getPublicPaths());
  }

  /** Registers {@code GET /who/me} in servlet web applications that have Spring MVC. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  @ConditionalOnClass(
      name = {
        "org.jwcarman.who.spring.security.web.WhoMeController",
        "org.springframework.web.servlet.DispatcherServlet"
      })
  static class WhoMeConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public WhoMeController whoMeController() {
      return new WhoMeController();
    }
  }

  /**
   * Hands errors raised in who's security filters to the application's Spring MVC exception
   * resolvers, so the application's {@code @RestControllerAdvice} formats them. {@code
   * DefaultErrorAttributes} is a resolver too, so the composite is picked by its bean name.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  @ConditionalOnClass(
      name = {
        "org.jwcarman.who.spring.security.web.WhoExceptionHandoff",
        "org.springframework.web.servlet.DispatcherServlet"
      })
  static class ExceptionHandoffConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public WhoExceptionHandoff whoExceptionHandoff(
        @Qualifier("handlerExceptionResolver") ObjectProvider<HandlerExceptionResolver> resolver) {
      HandlerExceptionResolver mvc = resolver.getIfAvailable();
      return new WhoExceptionHandoff(
          mvc == null
              ? null
              : (request, response, exception) ->
                  mvc.resolveException(request, response, null, exception) != null);
    }
  }

  /**
   * Turns on method security so {@code @RequiresPermission} is enforced rather than silently
   * ignored.
   */
  @Configuration(proxyBeanMethods = false)
  @EnableMethodSecurity
  @ConditionalOnClass(name = "org.jwcarman.who.spring.security.RequiresPermission")
  @ConditionalOnBooleanProperty(name = "who.method-security.enabled", matchIfMissing = true)
  @ConditionalOnMissingBean(
      type =
          "org.springframework.security.config.annotation.method.configuration.PrePostMethodSecurityConfiguration")
  static class MethodSecurityConfiguration {}
}
