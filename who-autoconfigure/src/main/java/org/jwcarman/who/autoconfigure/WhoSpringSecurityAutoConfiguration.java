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
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.jwcarman.who.core.event.WhoEventPublisher;
import org.jwcarman.who.core.repository.SessionRecordRepository;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.core.service.WhoSessions;
import org.jwcarman.who.spring.security.PasswordlessLoginMethod;
import org.jwcarman.who.spring.security.RecentSignInAuthorizationManager;
import org.jwcarman.who.spring.security.RecentSignInMethodInterceptor;
import org.jwcarman.who.spring.security.SessionEndpoints;
import org.jwcarman.who.spring.security.WhoPaths;
import org.jwcarman.who.spring.security.WhoPublicPaths;
import org.jwcarman.who.spring.security.WhoSessionEndListener;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.jwcarman.who.spring.security.web.LastSignInCookie;
import org.jwcarman.who.spring.security.web.WhoExceptionHandoff;
import org.jwcarman.who.spring.security.web.WhoMeController;
import org.springframework.aop.Advisor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.util.ClassUtils;
import org.springframework.web.servlet.HandlerExceptionResolver;

import tools.jackson.databind.json.JsonMapper;

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

  /** Spring Boot's own default for {@code server.servlet.session.timeout}. */
  private static final Duration DEFAULT_SESSION_TIMEOUT = Duration.ofMinutes(30);

  private static final String SPRING_SESSION_TIMEOUT = "spring.session.timeout";
  private static final String SERVLET_SESSION_TIMEOUT = "server.servlet.session.timeout";
  private static final String SPRING_SESSION_CLASS = "org.springframework.session.Session";

  /** Creates the autoconfiguration; Spring Boot calls it. */
  public WhoSpringSecurityAutoConfiguration() {
    // nothing to set up: every bean is built by a method below
  }

  /**
   * Keeps only a {@code WhoSession} (ids and the sign-in time) in the HTTP session and resolves the
   * principal again on every request, so a deactivated identity or a revoked credential takes
   * effect at once.
   *
   * @param whoService resolves the principal
   * @param whoSessions records each sign-in and says whether its session is live
   * @param clock stamps the sign-in time
   * @param whoEventPublisher publishes session events
   * @return the repository
   */
  @Bean
  @ConditionalOnMissingBean
  public WhoSessionSecurityContextRepository whoSessionSecurityContextRepository(
      WhoService whoService,
      WhoSessions whoSessions,
      Clock clock,
      WhoEventPublisher whoEventPublisher) {
    return new WhoSessionSecurityContextRepository(
        whoService, whoSessions, clock, whoEventPublisher);
  }

  /**
   * who's record of browser sessions. The idle timeout is Spring Boot's: {@code
   * spring.session.timeout} when set, else {@code server.servlet.session.timeout}, else 30 minutes.
   *
   * @param repository where sessions are kept
   * @param whoEventPublisher publishes ended and purged sessions
   * @param clock the current time
   * @param properties who's settings
   * @param environment where the timeout is read
   * @param resourceLoader tells whether Spring Session is present
   * @return the sessions
   */
  @Bean
  @ConditionalOnMissingBean
  public WhoSessions whoSessions(
      SessionRecordRepository repository,
      WhoEventPublisher whoEventPublisher,
      Clock clock,
      WhoProperties properties,
      Environment environment,
      ResourceLoader resourceLoader) {
    WhoProperties.Sessions settings = properties.getSessions();
    return new WhoSessions(
        repository,
        whoEventPublisher,
        clock,
        idleTimeout(
            environment,
            ClassUtils.isPresent(SPRING_SESSION_CLASS, resourceLoader.getClassLoader())),
        settings.getLastSeenInterval(),
        settings.getPurgeBatchSize(),
        settings.isAutoPurge());
  }

  /*
   * Reads the session timeout the way Spring Boot does: a bare number is seconds, an empty value is
   * unset, and spring.session.timeout counts only with Spring Session. who needs sessions to
   * expire, both to refuse an idle one and to know which records are dead.
   */
  static Duration idleTimeout(Environment environment, boolean springSession) {
    Binder binder = Binder.get(environment);
    List<String> names =
        springSession
            ? List.of(SPRING_SESSION_TIMEOUT, SERVLET_SESSION_TIMEOUT)
            : List.of(SERVLET_SESSION_TIMEOUT);
    for (String name : names) {
      String value = binder.bind(name, String.class).map(String::trim).orElse("");
      if (!value.isEmpty()) {
        Duration timeout = parse(name, value);
        if (timeout.isNegative() || timeout.isZero()) {
          throw new IllegalStateException(
              name
                  + "="
                  + value
                  + " never expires sessions, but who needs them to: set it to a positive"
                  + " duration such as 30m");
        }
        return timeout;
      }
    }
    return DEFAULT_SESSION_TIMEOUT;
  }

  private static Duration parse(String name, String value) {
    try {
      return DurationStyle.detectAndParse(value, ChronoUnit.SECONDS);
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException(
          name + "=" + value + " is not a duration: use a number of seconds or one like 30m", e);
    }
  }

  /**
   * Ends who's record of a session when the servlet container destroys it. Spring Boot registers it
   * with the embedded container.
   *
   * @param whoSessions who's record of sessions
   * @return the listener
   */
  @Bean
  @ConditionalOnMissingBean
  public WhoSessionEndListener whoSessionEndListener(WhoSessions whoSessions) {
    return new WhoSessionEndListener(whoSessions);
  }

  /**
   * {@code GET /who/sessions} and {@code DELETE /who/sessions/...}: a signed-in person's browser
   * sessions, and ending them. Ending another session needs a sign-in within {@code
   * who.security.recent-sign-in}.
   *
   * @param whoSessions who's record of sessions
   * @param repository reads the browser's who session and signs it out
   * @param handoff answers errors through the application's exception handling, when it has one
   * @param jsonMapper writes the answers; a default one when the application has none
   * @param clock the current time
   * @param properties who's properties, for the recent-sign-in window
   * @return the endpoints
   */
  @Bean
  @ConditionalOnMissingBean
  public SessionEndpoints whoSessionEndpoints(
      WhoSessions whoSessions,
      WhoSessionSecurityContextRepository repository,
      ObjectProvider<WhoExceptionHandoff> handoff,
      ObjectProvider<JsonMapper> jsonMapper,
      Clock clock,
      WhoProperties properties) {
    return new SessionEndpoints(
        whoSessions,
        repository,
        handoff.getIfAvailable(() -> new WhoExceptionHandoff(null)),
        jsonMapper.getIfAvailable(JsonMapper::new),
        clock,
        properties.getSecurity().getRecentSignIn());
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
   * Where every who endpoint lives: {@code who.base-path}, {@code /who} unless configured.
   *
   * @param properties who's properties
   * @return the paths
   * @throws WhoConfigurationException when {@code who.base-path} is not a usable root
   */
  @Bean
  @ConditionalOnMissingBean
  public WhoPaths whoPaths(WhoProperties properties) {
    try {
      return new WhoPaths(properties.getBasePath());
    } catch (IllegalArgumentException e) {
      throw new WhoConfigurationException(
          "who.base-path", e.getMessage(), "use a path such as /who or /auth");
    }
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
   * Decides whether the caller signed in within {@code who.security.recent-sign-in}: behind
   * {@code @RequiresRecentSignIn}, and for URL rules, {@code .access(recentSignIn)}.
   *
   * @param properties who's properties, for the window
   * @param clock the current time
   * @return the manager
   */
  @Bean
  @ConditionalOnMissingBean
  public RecentSignInAuthorizationManager whoRecentSignIn(WhoProperties properties, Clock clock) {
    return new RecentSignInAuthorizationManager(properties.getSecurity().getRecentSignIn(), clock);
  }

  /**
   * Enforces {@code @RequiresRecentSignIn}, whether method security was turned on by who or by the
   * application. {@code who.method-security.enabled} does not turn it off: it only says whether who
   * turns method security on, and an application that does so itself still has every
   * {@code @RequiresRecentSignIn} enforced, as its {@code @RequiresPermission} is.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "org.jwcarman.who.spring.security.RequiresRecentSignIn")
  static class RecentSignInMethodSecurityConfiguration {

    // only a static bean method: Spring creates this class, nothing else should
    RecentSignInMethodSecurityConfiguration() {}

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static Advisor whoRecentSignInInterceptor(
        ObjectProvider<RecentSignInAuthorizationManager> recentSignIn) {
      return RecentSignInMethodInterceptor.create(recentSignIn::getObject);
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
