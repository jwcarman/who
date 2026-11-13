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
package org.jwcarman.who.passkey;

import static java.util.Objects.requireNonNull;

import java.time.Clock;
import java.time.Duration;

import org.jwcarman.who.spring.security.PasswordlessLoginMethod;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.jwcarman.who.spring.security.web.JsonAuthenticationHandlers;
import org.jwcarman.who.spring.security.web.WhoExceptionHandoff;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ResolvableType;
import org.springframework.security.authentication.AuthenticationEventPublisher;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.webauthn.authentication.WebAuthnAuthenticationFilter;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;

import tools.jackson.databind.json.JsonMapper;

/**
 * Passkey sign-in and registration through Spring Security's {@code webAuthn()}, at its default
 * URLs, with who's collaborators where Spring hard-wires its own.
 *
 * <ul>
 *   <li>who's two repositories are shared with the chain, so Spring never looks them up as beans
 *       (two beans of one type would make it fall back to in-memory maps).
 *   <li>Spring requires a {@link UserDetailsService} even though who's provider never calls one; if
 *       the chain has none to find, a stub that finds no users is shared.
 *   <li>The sign-in filter gets who's provider, observed and publishing events as Spring's own
 *       managers do, {@link WhoSessionSecurityContextRepository}, the chain's shared {@link
 *       SessionAuthenticationStrategy} (so the session id changes and CSRF rotates at sign-in;
 *       Spring's default does neither) and who's JSON answers.
 *   <li>Spring's default registration page is off.
 *   <li>A {@link PasskeyRegistrationGuardFilter} supplies the registration answers Spring has no
 *       hook for.
 * </ul>
 *
 * <p>The provider and the guard are not beans: an {@link AuthenticationProvider} bean would join
 * the app's global authentication manager, and a filter bean would be registered as a servlet
 * filter.
 */
public class PasskeyLoginMethod implements PasswordlessLoginMethod {

  /** Satisfies Spring's {@code UserDetailsService} requirement; who's sign-in never calls it. */
  static final UserDetailsService NO_USERS =
      username -> {
        throw new UsernameNotFoundException("Passkey sign-in does not use a UserDetailsService");
      };

  private final JdbcWhoPasskeyUserRepository userRepository;
  private final JdbcWhoPasskeyCredentialRepository credentialRepository;
  private final AuthenticationProvider authenticationProvider;
  private final WhoSessionSecurityContextRepository sessionRepository;
  private final JsonMapper jsonMapper;
  private final Clock clock;
  private final Duration registrationWindow;

  /**
   * Creates the login method.
   *
   * @param userRepository who's user entities
   * @param credentialRepository who's credential records
   * @param authenticationProvider signs in with a verified passkey
   * @param sessionRepository stores the who session at sign-in
   * @param jsonMapper writes the sign-in success answer
   * @param clock the current time, for the registration window
   * @param registrationWindow how long after signing in a passkey may be registered
   */
  public PasskeyLoginMethod(
      JdbcWhoPasskeyUserRepository userRepository,
      JdbcWhoPasskeyCredentialRepository credentialRepository,
      AuthenticationProvider authenticationProvider,
      WhoSessionSecurityContextRepository sessionRepository,
      JsonMapper jsonMapper,
      Clock clock,
      Duration registrationWindow) {
    this.userRepository = requireNonNull(userRepository, "userRepository must not be null");
    this.credentialRepository =
        requireNonNull(credentialRepository, "credentialRepository must not be null");
    this.authenticationProvider =
        requireNonNull(authenticationProvider, "authenticationProvider must not be null");
    this.sessionRepository =
        requireNonNull(sessionRepository, "sessionRepository must not be null");
    this.jsonMapper = requireNonNull(jsonMapper, "jsonMapper must not be null");
    this.clock = requireNonNull(clock, "clock must not be null");
    this.registrationWindow =
        requireNonNull(registrationWindow, "registrationWindow must not be null");
  }

  @Override
  public void configure(HttpSecurity http) {
    ApplicationContext context = http.getSharedObject(ApplicationContext.class);
    WhoExceptionHandoff handoff =
        context
            .getBeanProvider(WhoExceptionHandoff.class)
            .getIfAvailable(() -> new WhoExceptionHandoff(null));
    JsonAuthenticationHandlers handlers = new JsonAuthenticationHandlers(jsonMapper, handoff);
    http.setSharedObject(PublicKeyCredentialUserEntityRepository.class, userRepository);
    http.setSharedObject(UserCredentialRepository.class, credentialRepository);
    // Spring finds a UserDetailsService as a shared object, then as a unique bean
    if (http.getSharedObject(UserDetailsService.class) == null
        && context.getBeanProvider(UserDetailsService.class).getIfUnique() == null) {
      http.setSharedObject(UserDetailsService.class, NO_USERS);
    }
    http.webAuthn(
        webAuthn ->
            webAuthn
                .disableDefaultRegistrationPage(true)
                .withObjectPostProcessor(signInPostProcessor(http, context, handlers)));
    // WebAuthn's own filters are added later, so anchor to a filter that is already there; this
    // sorts before all of them
    http.addFilterAfter(
        new PasskeyRegistrationGuardFilter(sessionRepository, clock, registrationWindow, handoff),
        ExceptionTranslationFilter.class);
  }

  /**
   * Applies what Spring applies to the authentication managers it builds. Spring's own {@code
   * webAuthn()} builds its manager by hand too and skips this, so passkey sign-ins would go
   * unobserved. Today that means an observation of each sign-in when the application has an {@code
   * ObservationRegistry} (Spring Boot provides one with Actuator), honoring the application's
   * {@code SecurityObservationSettings}.
   *
   * <p>Reported upstream as <a
   * href="https://github.com/spring-projects/spring-security/issues/20112">spring-security#20112</a>;
   * once fixed there, this is redundant but harmless.
   */
  private static AuthenticationManager postProcessed(
      ApplicationContext context, AuthenticationManager manager) {
    ObjectProvider<ObjectPostProcessor<AuthenticationManager>> postProcessor =
        context.getBeanProvider(
            ResolvableType.forClassWithGenerics(
                ObjectPostProcessor.class, AuthenticationManager.class));
    return postProcessor.getIfUnique(ObjectPostProcessor::identity).postProcess(manager);
  }

  // An anonymous class, not a lambda, so Spring can tell which type it post-processes.
  private ObjectPostProcessor<WebAuthnAuthenticationFilter> signInPostProcessor(
      HttpSecurity http, ApplicationContext context, JsonAuthenticationHandlers handlers) {
    return new ObjectPostProcessor<>() {
      @Override
      public <O extends WebAuthnAuthenticationFilter> O postProcess(O filter) {
        ProviderManager manager = new ProviderManager(authenticationProvider);
        context
            .getBeanProvider(AuthenticationEventPublisher.class)
            .ifUnique(manager::setAuthenticationEventPublisher);
        filter.setAuthenticationManager(postProcessed(context, manager));
        filter.setSecurityContextRepository(sessionRepository);
        // read now, during configure: SessionManagementConfigurer.init has shared it by then
        SessionAuthenticationStrategy sessionStrategy =
            http.getSharedObject(SessionAuthenticationStrategy.class);
        if (sessionStrategy == null) {
          throw new IllegalStateException(
              "Passkey sign-in needs the chain's session management (session fixation"
                  + " protection and CSRF rotation); it is disabled on this chain");
        }
        filter.setSessionAuthenticationStrategy(sessionStrategy);
        filter.setAuthenticationSuccessHandler(handlers.success());
        filter.setAuthenticationFailureHandler(handlers.failure());
        return filter;
      }
    };
  }
}
