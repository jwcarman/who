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

import java.util.function.Consumer;

import org.jwcarman.who.spring.security.WhoPaths;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.webauthn.authentication.PublicKeyCredentialRequestOptionsFilter;
import org.springframework.security.web.webauthn.authentication.WebAuthnAuthenticationFilter;
import org.springframework.security.web.webauthn.management.CredentialRecordOwnerAuthorizationManager;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;
import org.springframework.security.web.webauthn.registration.PublicKeyCredentialCreationOptionsFilter;
import org.springframework.security.web.webauthn.registration.PublicKeyCredentialCreationOptionsRepository;
import org.springframework.security.web.webauthn.registration.WebAuthnRegistrationFilter;

/**
 * Adds Spring Security's four passkey filters to the chain at who's URLs, in place of Spring's
 * {@code webAuthn()}, which fixes three of them at its own:
 *
 * <ul>
 *   <li>{@code POST <root>/passkey/register/options} and {@code POST <root>/passkey/register}
 *       register a passkey, and {@code DELETE <root>/passkey/register/{id}} removes one (Spring's
 *       owner check decides whose);
 *   <li>{@code POST <root>/passkey/sign-in/options} and {@code POST <root>/passkey/sign-in} sign
 *       in.
 * </ul>
 *
 * <p>The filters are Spring's, created and placed as {@code webAuthn()} creates and places them;
 * only the wiring is who's. It runs in the chain's configure phase, as {@code webAuthn()} does, so
 * the sign-in filter can be given the chain's session strategy.
 */
final class PasskeyFilters extends AbstractHttpConfigurer<PasskeyFilters, HttpSecurity> {

  /** Signs in, below the root. */
  static final String SIGN_IN = "/passkey/sign-in";

  /** The sign-in options, below the root. */
  static final String SIGN_IN_OPTIONS = "/passkey/sign-in/options";

  /** Registers a passkey, below the root. */
  static final String REGISTER = "/passkey/register";

  /** The registration options, below the root. */
  static final String REGISTER_OPTIONS = "/passkey/register/options";

  private final WhoPaths paths;
  private final JdbcWhoPasskeyUserRepository userRepository;
  private final JdbcWhoPasskeyCredentialRepository credentialRepository;
  private final Consumer<WebAuthnAuthenticationFilter> signInSetup;

  /**
   * Creates the configurer.
   *
   * @param paths where the passkey endpoints live
   * @param userRepository who's user entities, for the owner check on removal
   * @param credentialRepository who's credential records
   * @param signInSetup gives the sign-in filter who's collaborators, during the configure phase
   */
  PasskeyFilters(
      WhoPaths paths,
      JdbcWhoPasskeyUserRepository userRepository,
      JdbcWhoPasskeyCredentialRepository credentialRepository,
      Consumer<WebAuthnAuthenticationFilter> signInSetup) {
    this.paths = requireNonNull(paths, "paths must not be null");
    this.userRepository = requireNonNull(userRepository, "userRepository must not be null");
    this.credentialRepository =
        requireNonNull(credentialRepository, "credentialRepository must not be null");
    this.signInSetup = requireNonNull(signInSetup, "signInSetup must not be null");
  }

  @Override
  public void configure(HttpSecurity http) {
    ApplicationContext context = http.getSharedObject(ApplicationContext.class);
    WebAuthnRelyingPartyOperations relyingParty =
        context
            .getBeanProvider(WebAuthnRelyingPartyOperations.class)
            .getIfAvailable(
                () -> {
                  throw new IllegalStateException(
                      "Passkeys need a WebAuthnRelyingPartyOperations bean");
                });
    PublicKeyCredentialCreationOptionsRepository creationOptions =
        context.getBeanProvider(PublicKeyCredentialCreationOptionsRepository.class).getIfUnique();
    PathPatternRequestMatcher.Builder matchers = PathPatternRequestMatcher.withDefaults();

    WebAuthnAuthenticationFilter signIn = new WebAuthnAuthenticationFilter();
    signIn.setRequiresAuthenticationRequestMatcher(
        matchers.matcher(HttpMethod.POST, paths.of(SIGN_IN)));
    signInSetup.accept(signIn);
    signIn = postProcess(signIn);

    PublicKeyCredentialRequestOptionsFilter signInOptions =
        new PublicKeyCredentialRequestOptionsFilter(relyingParty);
    signInOptions.setRequestMatcher(matchers.matcher(HttpMethod.POST, paths.of(SIGN_IN_OPTIONS)));

    PublicKeyCredentialCreationOptionsFilter registrationOptions =
        new PublicKeyCredentialCreationOptionsFilter(relyingParty);
    registrationOptions.setRequestMatcher(
        matchers.matcher(HttpMethod.POST, paths.of(REGISTER_OPTIONS)));

    WebAuthnRegistrationFilter registration =
        new WebAuthnRegistrationFilter(credentialRepository, relyingParty);
    registration.setRegisterCredentialMatcher(
        matchers.matcher(HttpMethod.POST, paths.of(REGISTER)));
    // Spring reads the passkey's id from the {id} variable
    registration.setRemoveCredentialMatcher(
        matchers.matcher(HttpMethod.DELETE, paths.of(REGISTER + "/{id}")));
    registration.setDeleteCredentialAuthorizationManager(
        new CredentialRecordOwnerAuthorizationManager(credentialRepository, userRepository));

    if (creationOptions != null) {
      registration.setCreationOptionsRepository(creationOptions);
      registrationOptions.setCreationOptionsRepository(creationOptions);
    }

    // where Spring's webAuthn() puts them
    http.addFilterBefore(signIn, BasicAuthenticationFilter.class);
    http.addFilterAfter(registration, AuthorizationFilter.class);
    http.addFilterBefore(registrationOptions, AuthorizationFilter.class);
    http.addFilterBefore(signInOptions, AuthorizationFilter.class);
  }
}
