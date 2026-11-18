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
package org.jwcarman.who.oidc;

import static java.util.Objects.requireNonNull;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jwcarman.who.core.event.WhoEventPublisher;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.federated.FederatedCredentialRepository;
import org.jwcarman.who.spring.security.PasswordlessLoginMethod;
import org.jwcarman.who.spring.security.WhoPaths;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.jwcarman.who.spring.security.web.LastSignInCookie;
import org.jwcarman.who.spring.security.web.WhoExceptionHandoff;
import org.springframework.context.ApplicationContext;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.authentication.OAuth2LoginAuthenticationToken;
import org.springframework.security.oauth2.client.oidc.authentication.OidcAuthorizationCodeAuthenticationProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.NullSecurityContextRepository;

/**
 * Sign-in with Google, GitHub or any OpenID Connect provider, mounted with Spring Security's {@code
 * oauth2Login()}. A provider account who knows signs in; one it does not becomes a pending sign-up
 * the person confirms. Spring's token, the access token and the authorized client are never kept.
 */
public final class OidcLoginMethod implements PasswordlessLoginMethod {

  /** Where a sign-in starts, followed by the registration's id, below the root. */
  private static final String AUTHORIZE = "/oidc/authorize";

  /** Where the provider sends the browser back, followed by the registration's id. */
  private static final String CALLBACK = "/oidc/callback";

  private final WhoPaths paths;
  private final SignInRegistrations registrations;
  private final WhoService whoService;
  private final FederatedCredentialRepository credentials;
  private final WhoSessionSecurityContextRepository sessions;
  private final OidcSignUpService signUpService;
  private final IdentityRepository identities;
  private final OidcRedirects redirects;
  private final Clock clock;
  private final Duration signUpWindow;
  private final Duration linkWindow;
  private final PendingSignUps pendingSignUps = new PendingSignUps();
  private final AuthorizationRequestRepository<OAuth2AuthorizationRequest> authorizationRequests =
      new HttpSessionOAuth2AuthorizationRequestRepository();

  /**
   * Creates the login method.
   *
   * @param paths where the sign-in endpoints and the callback live
   * @param registrations the registrations who signs in with
   * @param whoService resolves a credential to its identity
   * @param credentials finds the credential for an issuer and subject
   * @param sessions stores the who session
   * @param signUpService confirms pending sign-ups and links
   * @param identities finds the identity signed in, for a link
   * @param redirects where the browser goes after a provider sign-in
   * @param clock stamps events and expiries
   * @param signUpWindow how long a pending sign-up lasts
   * @param linkWindow how recently the identity must have signed in to link an account
   */
  public OidcLoginMethod(
      WhoPaths paths,
      SignInRegistrations registrations,
      WhoService whoService,
      FederatedCredentialRepository credentials,
      WhoSessionSecurityContextRepository sessions,
      OidcSignUpService signUpService,
      IdentityRepository identities,
      OidcRedirects redirects,
      Clock clock,
      Duration signUpWindow,
      Duration linkWindow) {
    this.paths = requireNonNull(paths, "paths must not be null");
    this.registrations = requireNonNull(registrations, "registrations must not be null");
    this.whoService = requireNonNull(whoService, "whoService must not be null");
    this.credentials = requireNonNull(credentials, "credentials must not be null");
    this.sessions = requireNonNull(sessions, "sessions must not be null");
    this.signUpService = requireNonNull(signUpService, "signUpService must not be null");
    this.identities = requireNonNull(identities, "identities must not be null");
    this.redirects = requireNonNull(redirects, "redirects must not be null");
    this.clock = requireNonNull(clock, "clock must not be null");
    this.signUpWindow = requireNonNull(signUpWindow, "signUpWindow must not be null");
    this.linkWindow = requireNonNull(linkWindow, "linkWindow must not be null");
  }

  /**
   * The providers a sign-in page draws buttons for, one per registration who signs in with, in the
   * order the application declared them: {@code {"providers": [{"id": registrationId, "name":
   * clientName}]}}.
   */
  @Override
  public Map<String, Object> signInOptions() {
    List<Map<String, String>> providers =
        registrations.all().stream()
            .map(
                registration ->
                    Map.of(
                        "id",
                        registration.registrationId(),
                        "name",
                        registrations.name(registration.registrationId())))
            .toList();
    return Map.of("providers", providers);
  }

  @Override
  public Set<String> publicPaths() {
    return Set.of(
        paths.of(AUTHORIZE + "/**"),
        paths.of(CALLBACK + "/**"),
        paths.of(PendingSignUpFilter.PENDING),
        paths.of(PendingSignUpFilter.SIGN_UP));
  }

  @Override
  public void configure(HttpSecurity http) {
    ApplicationContext context = http.getSharedObject(ApplicationContext.class);
    ClientRegistrationRepository clients =
        context
            .getBeanProvider(ClientRegistrationRepository.class)
            .getIfAvailable(
                () -> {
                  throw new IllegalStateException(
                      "Provider sign-in needs client registrations"
                          + " (spring.security.oauth2.client.registration.*)");
                });
    // the configurer shares the repository with the whole chain; restore what was there, so an
    // application's own oauth2Client() keeps Spring Boot's
    OAuth2AuthorizedClientRepository shared =
        http.getSharedObject(OAuth2AuthorizedClientRepository.class);
    // likewise the registrations: who's callback is for who's sign-in only
    ClientRegistrationRepository sharedClients =
        http.getSharedObject(ClientRegistrationRepository.class);
    ClientRegistrationRepository callbacks =
        new CallbackRegistrations(clients, registrations, paths);
    OidcAuthorizationRequests resolver =
        new OidcAuthorizationRequests(callbacks, registrations, paths.of(AUTHORIZE));
    http.oauth2Login(
        login ->
            login
                // a login page of who's failure URL turns off Spring's generated one
                .loginPage(redirects.failureUrl())
                .clientRegistrationRepository(callbacks)
                .authorizedClientRepository(new NothingStoredAuthorizedClientRepository())
                .authorizationEndpoint(
                    endpoint ->
                        endpoint
                            .baseUri(paths.of(AUTHORIZE))
                            .authorizationRequestResolver(resolver)
                            .authorizationRequestRepository(authorizationRequests))
                .redirectionEndpoint(endpoint -> endpoint.baseUri(paths.of(CALLBACK + "/*")))
                .userInfoEndpoint(
                    userInfo -> userInfo.userService(new WhoOAuth2UserService(registrations::find)))
                .withObjectPostProcessor(idTokenIssuerCheck())
                .withObjectPostProcessor(signInPostProcessor(http, context, resolver)));
    http.setSharedObject(OAuth2AuthorizedClientRepository.class, shared);
    http.setSharedObject(ClientRegistrationRepository.class, sharedClients);
  }

  private static OAuth2AuthenticationToken signInResult(OAuth2LoginAuthenticationToken result) {
    String registrationId = result.getClientRegistration().getRegistrationId();
    return LinkIntent.readFrom(
            result.getAuthorizationExchange().getAuthorizationRequest().getAttributes())
        .<OAuth2AuthenticationToken>map(
            intent ->
                new LinkingAuthenticationToken(
                    result.getPrincipal(), result.getAuthorities(), registrationId, intent))
        .orElseGet(
            () ->
                new OAuth2AuthenticationToken(
                    result.getPrincipal(), result.getAuthorities(), registrationId));
  }

  // Anonymous classes, not lambdas, so Spring can tell which type each post-processes.
  private ObjectPostProcessor<OidcAuthorizationCodeAuthenticationProvider> idTokenIssuerCheck() {
    return new ObjectPostProcessor<>() {
      @Override
      public <O extends OidcAuthorizationCodeAuthenticationProvider> O postProcess(O provider) {
        provider.setJwtDecoderFactory(new IssuerValidatingIdTokenDecoderFactory(registrations));
        return provider;
      }
    };
  }

  private ObjectPostProcessor<OAuth2LoginAuthenticationFilter> signInPostProcessor(
      HttpSecurity http, ApplicationContext context, OidcAuthorizationRequests resolver) {
    return new ObjectPostProcessor<>() {
      @Override
      public <O extends OAuth2LoginAuthenticationFilter> O postProcess(O filter) {
        // read now, during configure: SessionManagementConfigurer.init has shared it by then
        SessionAuthenticationStrategy sessionStrategy =
            http.getSharedObject(SessionAuthenticationStrategy.class);
        if (sessionStrategy == null) {
          throw new IllegalStateException(
              "Provider sign-in needs the chain's session management (session fixation"
                  + " protection and CSRF rotation); it is disabled on this chain");
        }
        WhoEventPublisher eventPublisher =
            context
                .getBeanProvider(WhoEventPublisher.class)
                .getIfAvailable(() -> WhoEventPublisher.NOOP);
        LastSignInCookie lastSignInCookie =
            context.getBeanProvider(LastSignInCookie.class).getIfAvailable();
        OidcSignInHandler handler =
            new OidcSignInHandler(
                registrations,
                whoService,
                credentials,
                sessions,
                sessionStrategy,
                pendingSignUps,
                redirects,
                eventPublisher,
                lastSignInCookie,
                clock,
                signUpWindow,
                linkWindow);
        WhoExceptionHandoff handoff = WhoExceptionHandoff.from(context);
        // added here, where the session strategy is known; sorts before authorization, as
        // passkeys' guard filter does
        http.addFilterAfter(
            new LinkStartFilter(
                paths, resolver, authorizationRequests, sessions, handoff, clock, linkWindow),
            ExceptionTranslationFilter.class);
        http.addFilterAfter(
            new PendingSignUpFilter(
                paths,
                signUpService,
                identities,
                sessions,
                sessionStrategy,
                pendingSignUps,
                handoff,
                eventPublisher,
                lastSignInCookie,
                clock,
                linkWindow),
            ExceptionTranslationFilter.class);
        // Spring's token is not a who sign-in: no session strategy for it, and no saving it
        filter.setSessionAuthenticationStrategy(new NullAuthenticatedSessionStrategy());
        filter.setSecurityContextRepository(new NullSecurityContextRepository());
        // keep a link's intent, which Spring drops with the authorization request
        filter.setAuthenticationResultConverter(OidcLoginMethod::signInResult);
        filter.setAuthenticationSuccessHandler(handler);
        filter.setAuthenticationFailureHandler(handler);
        return filter;
      }
    };
  }

  @Override
  public String name() {
    List<String> ids =
        registrations.all().stream().map(SignInRegistration::registrationId).sorted().toList();
    return ids.isEmpty() ? "oidc" : "oidc (" + String.join(", ", ids) + ")";
  }
}
