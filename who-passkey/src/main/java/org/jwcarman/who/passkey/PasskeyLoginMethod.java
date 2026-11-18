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
import java.util.Map;
import java.util.Set;

import org.jwcarman.who.core.event.WhoEventPublisher;
import org.jwcarman.who.core.service.LastCredentialGuard;
import org.jwcarman.who.spring.security.PasswordlessLoginMethod;
import org.jwcarman.who.spring.security.WhoPaths;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.jwcarman.who.spring.security.web.JsonAuthenticationHandlers;
import org.jwcarman.who.spring.security.web.LastSignInCookie;
import org.jwcarman.who.spring.security.web.WhoExceptionHandoff;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ResolvableType;
import org.springframework.security.authentication.AuthenticationEventPublisher;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.webauthn.authentication.WebAuthnAuthenticationFilter;

import tools.jackson.databind.json.JsonMapper;

/**
 * Passkey sign-in and registration with Spring Security's passkey filters, at who's URLs under
 * {@code who.base-path}. who adds the filters itself (see {@link PasskeyFilters}) rather than
 * calling Spring's {@code webAuthn()}, which fixes their URLs, and gives them who's collaborators:
 *
 * <ul>
 *   <li>who's two repositories and the application's {@code WebAuthnRelyingPartyOperations} bean.
 *   <li>The sign-in filter gets who's provider, observed and publishing events as Spring's own
 *       managers do, {@link WhoSessionSecurityContextRepository}, the chain's shared {@link
 *       SessionAuthenticationStrategy} (so the session id changes and CSRF rotates at sign-in;
 *       Spring's default does neither) and who's JSON answers.
 *   <li>Spring's default registration page is off.
 *   <li>A {@link PasskeyRegistrationGuardFilter} supplies the registration answers Spring has no
 *       hook for, and a {@code PasskeyCredentialsFilter} serves {@code GET /me/passkeys} and {@code
 *       DELETE /me/passkeys/{credentialId}}, which Spring's filters do not.
 *   <li>The success handler and the guard publish through the application's {@link
 *       WhoEventPublisher} bean, if it has one: a successful sign-in publishes {@code
 *       SignInSucceeded} once its session is saved.
 * </ul>
 *
 * <p>The provider and the two filters are not beans: an {@link AuthenticationProvider} bean would
 * join the app's global authentication manager, and a filter bean would be registered as a servlet
 * filter.
 */
public class PasskeyLoginMethod implements PasswordlessLoginMethod {

  private final WhoPaths paths;
  private final JdbcWhoPasskeyUserRepository userRepository;
  private final JdbcWhoPasskeyCredentialRepository credentialRepository;
  private final AuthenticationProvider authenticationProvider;
  private final WhoSessionSecurityContextRepository sessionRepository;
  private final JsonMapper jsonMapper;
  private final Clock clock;
  private final Duration registrationWindow;
  private final PasskeyService passkeyService;
  private final LastCredentialGuard lastCredentialGuard;

  /**
   * Creates the login method.
   *
   * @param paths where the passkey endpoints live
   * @param userRepository who's user entities
   * @param credentialRepository who's credential records
   * @param authenticationProvider signs in with a verified passkey
   * @param sessionRepository stores the who session at sign-in
   * @param jsonMapper writes the sign-in success answer
   * @param clock the current time, for the registration window
   * @param registrationWindow how long after signing in a passkey may be registered; also how long
   *     after signing in one may be removed
   * @param passkeyService lists and revokes passkeys, for {@code /me/passkeys}
   * @param lastCredentialGuard keeps an identity's last sign-in credential from being removed
   */
  public PasskeyLoginMethod(
      WhoPaths paths,
      JdbcWhoPasskeyUserRepository userRepository,
      JdbcWhoPasskeyCredentialRepository credentialRepository,
      AuthenticationProvider authenticationProvider,
      WhoSessionSecurityContextRepository sessionRepository,
      JsonMapper jsonMapper,
      Clock clock,
      Duration registrationWindow,
      PasskeyService passkeyService,
      LastCredentialGuard lastCredentialGuard) {
    this.paths = requireNonNull(paths, "paths must not be null");
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
    this.passkeyService = requireNonNull(passkeyService, "passkeyService must not be null");
    this.lastCredentialGuard =
        requireNonNull(lastCredentialGuard, "lastCredentialGuard must not be null");
  }

  /** The endpoints a signed-out browser uses to sign in with a passkey. */
  @Override
  public Set<String> publicPaths() {
    return Set.of(paths.of(PasskeyFilters.SIGN_IN_OPTIONS), paths.of(PasskeyFilters.SIGN_IN));
  }

  /** Passkey sign-in is on: this method exists only when the module is. */
  @Override
  public Map<String, Object> signInOptions() {
    return Map.of("passkey", true);
  }

  @Override
  public void configure(HttpSecurity http) {
    ApplicationContext context = http.getSharedObject(ApplicationContext.class);
    WhoExceptionHandoff handoff = WhoExceptionHandoff.from(context);
    WhoEventPublisher eventPublisher =
        context
            .getBeanProvider(WhoEventPublisher.class)
            .getIfAvailable(() -> WhoEventPublisher.NOOP);
    JsonAuthenticationHandlers handlers =
        new JsonAuthenticationHandlers(
            jsonMapper,
            handoff,
            eventPublisher,
            clock,
            context.getBeanProvider(LastSignInCookie.class).getIfAvailable());
    http.with(
        new PasskeyFilters(
            paths,
            userRepository,
            credentialRepository,
            signIn -> setUpSignIn(signIn, http, context, handlers)),
        Customizer.withDefaults());
    // the passkey filters are added later, so anchor to a filter that is already there; this
    // sorts before all of them
    http.addFilterAfter(
        new PasskeyRegistrationGuardFilter(
            paths, sessionRepository, clock, registrationWindow, handoff, eventPublisher),
        ExceptionTranslationFilter.class);
    http.addFilterAfter(
        new PasskeyCredentialsFilter(
            paths,
            sessionRepository,
            passkeyService,
            lastCredentialGuard,
            handoff,
            clock,
            registrationWindow),
        ExceptionTranslationFilter.class);
  }

  /**
   * Applies what Spring applies to the authentication managers it builds. who builds the passkey
   * manager by hand, as Spring's own {@code webAuthn()} does, so without this passkey sign-ins
   * would go unobserved. Today that means an observation of each sign-in when the application has
   * an {@code ObservationRegistry} (Spring Boot provides one with Actuator), honoring the
   * application's {@code SecurityObservationSettings}.
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

  /** Gives the sign-in filter who's collaborators; runs in the chain's configure phase. */
  private void setUpSignIn(
      WebAuthnAuthenticationFilter filter,
      HttpSecurity http,
      ApplicationContext context,
      JsonAuthenticationHandlers handlers) {
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
  }

  @Override
  public String name() {
    return "passkey";
  }
}
