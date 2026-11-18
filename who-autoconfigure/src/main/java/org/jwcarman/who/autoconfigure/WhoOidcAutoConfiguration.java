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

import static java.util.Objects.requireNonNullElse;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jwcarman.who.core.event.WhoEventPublisher;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.core.service.LastCredentialGuard;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.core.signup.ClosedSignUpPolicy;
import org.jwcarman.who.core.signup.WhoSignUpPolicy;
import org.jwcarman.who.federated.FederatedCredentialRepository;
import org.jwcarman.who.federated.FederatedCredentialService;
import org.jwcarman.who.oidc.CallbackRegistrations;
import org.jwcarman.who.oidc.InvalidRegistrationException;
import org.jwcarman.who.oidc.LinkedAccountsController;
import org.jwcarman.who.oidc.OidcLoginMethod;
import org.jwcarman.who.oidc.OidcRedirects;
import org.jwcarman.who.oidc.OidcSignInCredentialCounter;
import org.jwcarman.who.oidc.OidcSignUpService;
import org.jwcarman.who.oidc.ProviderSettings;
import org.jwcarman.who.oidc.SignInRegistrations;
import org.jwcarman.who.spring.security.WhoPaths;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.server.PathContainer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Registers provider sign-in (Google, GitHub, any OpenID Connect provider) when {@code who-oidc} is
 * on the classpath. It needs at least one {@code authorization_code} client registration.
 */
@AutoConfiguration(
    after = {WhoFederatedAutoConfiguration.class, WhoSpringSecurityAutoConfiguration.class},
    afterName =
        "org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration")
@EnableConfigurationProperties(WhoProperties.class)
@ConditionalOnClass(name = "org.jwcarman.who.oidc.OidcLoginMethod")
@ConditionalOnBooleanProperty(name = "who.oidc.enabled", matchIfMissing = true)
public class WhoOidcAutoConfiguration {

  private static final Logger log = LoggerFactory.getLogger(WhoOidcAutoConfiguration.class);

  private static final String OFF_SWITCH =
      " Or turn provider sign-in off with who.oidc.enabled=false.";

  /** Creates the autoconfiguration; Spring Boot calls it. */
  public WhoOidcAutoConfiguration() {
    // nothing to set up: every bean is built by a method below
  }

  /**
   * The sign-up policy: closed, so nobody signs up until the application says who may.
   *
   * @return the policy
   */
  @Bean
  @ConditionalOnMissingBean(WhoSignUpPolicy.class)
  public ClosedSignUpPolicy whoSignUpPolicy() {
    return new ClosedSignUpPolicy();
  }

  /**
   * Counts the linked accounts a configured registration signs in, for the last-credential rule; an
   * account whose registration is gone, or a JWT's issuer and subject, is not counted.
   *
   * @param jdbcClient the database
   * @param registrations the registrations who signs in with
   * @return the counter
   */
  @Bean
  @ConditionalOnMissingBean
  public OidcSignInCredentialCounter oidcSignInCredentialCounter(
      JdbcClient jdbcClient, SignInRegistrations registrations) {
    return new OidcSignInCredentialCounter(jdbcClient, registrations);
  }

  /**
   * The client registrations who signs in with, checked at startup.
   *
   * @param clientRegistrations Spring Boot's client registrations
   * @param paths where who's callback lives
   * @param properties who's properties
   * @return the registrations
   */
  @Bean
  @ConditionalOnMissingBean
  public SignInRegistrations signInRegistrations(
      ObjectProvider<ClientRegistrationRepository> clientRegistrations,
      WhoPaths paths,
      WhoProperties properties) {
    List<ClientRegistration> registrations = new ArrayList<>();
    if (clientRegistrations.getIfAvailable() instanceof Iterable<?> all) {
      all.forEach(
          registration -> {
            if (registration instanceof ClientRegistration client) {
              registrations.add(client);
            }
          });
    }
    Map<String, ProviderSettings> settings = new LinkedHashMap<>();
    properties
        .getOidc()
        .getProviders()
        .forEach(
            (id, provider) ->
                settings.put(
                    id,
                    new ProviderSettings(
                        provider.isEnabled(),
                        provider.getIssuer(),
                        provider.getSubjectAttribute(),
                        provider.getTrustEmail())));
    SignInRegistrations signIn;
    try {
      signIn = SignInRegistrations.of(registrations, settings);
    } catch (InvalidRegistrationException e) {
      throw new WhoConfigurationException(
          e.property(), e.problem(), "Correct the client registration." + OFF_SWITCH);
    }
    if (signIn.isEmpty()) {
      throw new WhoConfigurationException(
          "spring.security.oauth2.client.registration",
          "names no provider to sign in with",
          "Add a registration with the authorization_code grant." + OFF_SWITCH);
    }
    for (ClientRegistration registration : registrations) {
      if (signIn.find(registration.getRegistrationId()).isPresent()) {
        CallbackRegistrations.problem(registration, paths)
            .ifPresent(
                problem -> {
                  throw new WhoConfigurationException(
                      "spring.security.oauth2.client.registration."
                          + registration.getRegistrationId()
                          + ".redirect-uri",
                      problem,
                      "Remove it, so who sets it, or end it with "
                          + CallbackRegistrations.callbackPath(paths)
                          + "."
                          + OFF_SWITCH);
                });
      }
    }
    return signIn;
  }

  /**
   * Confirms pending sign-ups through the policy, and links.
   *
   * @param policy the sign-up policy
   * @param whoService resolves credentials
   * @param identities stores identities
   * @param credentials stores provider credentials
   * @param credentialService links provider credentials
   * @param credentialIdentities links credentials to identities
   * @param eventPublisher where events are published
   * @param clock stamps events
   * @param transactionManager runs each confirmation in a transaction
   * @return the service
   */
  @Bean
  @ConditionalOnMissingBean
  public OidcSignUpService oidcSignUpService(
      WhoSignUpPolicy policy,
      WhoService whoService,
      IdentityRepository identities,
      FederatedCredentialRepository credentials,
      FederatedCredentialService credentialService,
      CredentialIdentityRepository credentialIdentities,
      WhoEventPublisher eventPublisher,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    return new OidcSignUpService(
        policy,
        whoService,
        identities,
        credentials,
        credentialService,
        credentialIdentities,
        eventPublisher,
        clock,
        transactionManager);
  }

  /**
   * Mounts provider sign-in in who's chains.
   *
   * @param paths where the sign-in endpoints and the callback live
   * @param registrations the registrations who signs in with
   * @param whoService resolves credentials
   * @param credentials finds provider credentials
   * @param sessions stores the who session
   * @param signUpService confirms pending sign-ups and links
   * @param identities finds the identity signed in
   * @param clock stamps events and expiries
   * @param properties who's properties
   * @return the login method
   */
  @Bean
  @ConditionalOnMissingBean
  public OidcLoginMethod oidcLoginMethod(
      WhoPaths paths,
      SignInRegistrations registrations,
      WhoService whoService,
      FederatedCredentialRepository credentials,
      WhoSessionSecurityContextRepository sessions,
      OidcSignUpService signUpService,
      IdentityRepository identities,
      Clock clock,
      WhoProperties properties) {
    WhoProperties.Oidc oidc = properties.getOidc();
    String successUrl = localPath("who.oidc.success-url", oidc.getSuccessUrl());
    String failureUrl = localPath("who.oidc.failure-url", oidc.getFailureUrl());
    String linkUrl =
        oidc.getLinkUrl() == null ? successUrl : localPath("who.oidc.link-url", oidc.getLinkUrl());
    warnWhenNotPublic(failureUrl, properties.getSecurity().getPublicPaths());
    return new OidcLoginMethod(
        paths,
        registrations,
        whoService,
        credentials,
        sessions,
        signUpService,
        identities,
        new OidcRedirects(successUrl, failureUrl, linkUrl),
        clock,
        oidc.getSignUpWindow(),
        properties.getSecurity().getRecentSignIn());
  }

  /**
   * {@code GET /who/me/accounts} and {@code DELETE /who/me/accounts/{id}}: the caller's linked
   * provider accounts, listed by any signed-in caller and unlinked from a browser session.
   *
   * @param registrations names each account's provider
   * @param accounts lists and unlinks the caller's accounts
   * @param lastCredentialGuard required: accounts are never unlinked without the last-credential
   *     rule, so an application without it fails to start
   * @param sessions tells a browser session from a key or token
   * @param clock the current time
   * @param properties {@code who.security.recent-sign-in}
   * @return the controller
   */
  @Bean
  @ConditionalOnMissingBean
  public LinkedAccountsController whoLinkedAccountsController(
      SignInRegistrations registrations,
      FederatedCredentialService accounts,
      LastCredentialGuard lastCredentialGuard,
      WhoSessionSecurityContextRepository sessions,
      Clock clock,
      WhoProperties properties) {
    return new LinkedAccountsController(
        registrations,
        accounts,
        lastCredentialGuard,
        sessions,
        clock,
        properties.getSecurity().getRecentSignIn());
  }

  private static String localPath(String property, String url) {
    if (!OidcRedirects.isLocalPath(url)) {
      throw new WhoConfigurationException(
          property,
          "must be a path on this site, such as /signin",
          "Set it to a path that starts with a single /." + OFF_SWITCH);
    }
    return url;
  }

  /**
   * who does not make the failure URL public; a signed-out person sent there needs it to be. The
   * URL is a local path by now, so it has a path; Spring declares it nullable, so "/" stands in.
   */
  private static void warnWhenNotPublic(String failureUrl, List<String> publicPaths) {
    String path = UriComponentsBuilder.fromUriString(failureUrl).build().getPath();
    PathContainer container = PathContainer.parsePath(requireNonNullElse(path, "/"));
    boolean isPublic =
        publicPaths.stream()
            .map(PathPatternParser.defaultInstance::parse)
            .anyMatch(pattern -> pattern.matches(container));
    if (!isPublic) {
      log.warn(
          "who.oidc.failure-url {} is not in who.security.public-paths; a signed-out person sent"
              + " there gets a 401",
          failureUrl);
    }
  }
}
