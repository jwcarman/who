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

import java.util.Optional;
import java.util.UUID;

import org.jwcarman.who.spring.security.WhoAuthenticationToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.webauthn.api.AuthenticatorSelectionCriteria;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRpEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.ResidentKeyRequirement;
import org.springframework.security.web.webauthn.api.UserVerificationRequirement;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialCreationOptionsRequest;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialRequestOptionsRequest;
import org.springframework.security.web.webauthn.management.RelyingPartyAuthenticationRequest;
import org.springframework.security.web.webauthn.management.RelyingPartyRegistrationRequest;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;
import org.springframework.security.web.webauthn.management.Webauthn4JRelyingPartyOperations;

/**
 * who's relying party: Spring's {@link Webauthn4JRelyingPartyOperations}, configured from {@link
 * PasskeySettings}, with three changes.
 *
 * <ul>
 *   <li>Registration and sign-in require user verification; registration requires a resident key.
 *       Spring enforces both from the options it stored in the session, so a client cannot
 *       downgrade them.
 *   <li>Creation options first create who's user entity for the identity (a 64-byte user handle),
 *       so Spring never creates its own, and name it with the {@link PasskeyNameResolver}. The name
 *       is refreshed on every creation-options call, so an identity that gains or changes an
 *       address gets it on its next registration. Devices keep the name they stored.
 *   <li>A registration is refused unless the creation options in the session belong to the identity
 *       signed in now. Spring takes the owner from the stored options alone, so options left in a
 *       session by one identity could otherwise register a passkey for it after another identity
 *       signs in on the same session.
 *   <li>A successful registration is reported to the {@link PasskeyAddedNotifier}.
 * </ul>
 */
public class WhoRelyingPartyOperations implements WebAuthnRelyingPartyOperations {

  private static final Logger log = LoggerFactory.getLogger(WhoRelyingPartyOperations.class);

  private final AuthenticationTrustResolver trustResolver = new AuthenticationTrustResolverImpl();
  private final Webauthn4JRelyingPartyOperations delegate;
  private final JdbcWhoPasskeyUserRepository userRepository;
  private final PasskeyNameResolver nameResolver;
  private final PasskeyAddedNotifier notifier;

  /**
   * Creates the relying party.
   *
   * @param settings the relying party settings
   * @param userRepository who's user entities
   * @param credentialRepository who's credential records
   * @param nameResolver picks a new user entity's picker name
   * @param notifier told after each successful registration
   */
  public WhoRelyingPartyOperations(
      PasskeySettings settings,
      JdbcWhoPasskeyUserRepository userRepository,
      JdbcWhoPasskeyCredentialRepository credentialRepository,
      PasskeyNameResolver nameResolver,
      PasskeyAddedNotifier notifier) {
    requireNonNull(settings, "settings must not be null");
    requireNonNull(credentialRepository, "credentialRepository must not be null");
    this.userRepository = requireNonNull(userRepository, "userRepository must not be null");
    this.nameResolver = requireNonNull(nameResolver, "nameResolver must not be null");
    this.notifier = requireNonNull(notifier, "notifier must not be null");
    this.delegate =
        new Webauthn4JRelyingPartyOperations(
            userRepository,
            credentialRepository,
            PublicKeyCredentialRpEntity.builder()
                .id(settings.rpId())
                .name(settings.rpName())
                .build(),
            settings.allowedOrigins());
    // Replaces Spring's whole criteria, so the resident key requirement is restated.
    delegate.setCustomizeCreationOptions(
        options ->
            options.authenticatorSelection(
                AuthenticatorSelectionCriteria.builder()
                    .userVerification(UserVerificationRequirement.REQUIRED)
                    .residentKey(ResidentKeyRequirement.REQUIRED)
                    .build()));
    delegate.setCustomizeRequestOptions(
        options -> options.userVerification(UserVerificationRequirement.REQUIRED));
  }

  /**
   * Creates who's user entity for the signed-in identity, if it has none, or refreshes its name,
   * then delegates.
   *
   * @throws IllegalArgumentException if the request is not authenticated, or the authentication's
   *     name is not an identity id
   */
  @Override
  public PublicKeyCredentialCreationOptions createPublicKeyCredentialCreationOptions(
      PublicKeyCredentialCreationOptionsRequest request) {
    requireNonNull(request, "request must not be null");
    Authentication authentication = request.getAuthentication();
    if (!trustResolver.isAuthenticated(authentication)) {
      throw new IllegalArgumentException("Authentication must be authenticated");
    }
    UUID identityId = identityId(authentication);
    String name = nameResolver.nameFor(identityId, credentialId(authentication));
    if (userRepository.findByUsername(identityId.toString()) == null) {
      userRepository.create(identityId, name);
    } else {
      // the identity may have gained or changed an address since its last registration
      userRepository.rename(identityId, name);
    }
    return delegate.createPublicKeyCredentialCreationOptions(request);
  }

  /**
   * Checks that the creation options belong to the signed-in identity, delegates, then tells the
   * notifier; a notifier failure is logged and not rethrown.
   *
   * @throws IllegalStateException if no identity is signed in, or the options' user entity is not
   *     the signed-in identity's
   */
  @Override
  public CredentialRecord registerCredential(RelyingPartyRegistrationRequest request) {
    requireNonNull(request, "request must not be null");
    requireOptionsOfCurrentIdentity(request.getCreationOptions());
    CredentialRecord record =
        JdbcWhoPasskeyCredentialRepository.registering(() -> delegate.registerCredential(request));
    userRepository
        .findIdentityId(record.getUserEntityUserId())
        .ifPresent(identityId -> notify(identityId, record.getLabel()));
    return record;
  }

  @Override
  public PublicKeyCredentialRequestOptions createCredentialRequestOptions(
      PublicKeyCredentialRequestOptionsRequest request) {
    return delegate.createCredentialRequestOptions(request);
  }

  @Override
  public PublicKeyCredentialUserEntity authenticate(RelyingPartyAuthenticationRequest request) {
    return delegate.authenticate(request);
  }

  private void requireOptionsOfCurrentIdentity(PublicKeyCredentialCreationOptions options) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (!trustResolver.isAuthenticated(authentication)) {
      throw new IllegalStateException("registering a passkey requires a signed-in identity");
    }
    Optional<UUID> current = parseUuid(authentication.getName());
    Optional<UUID> owner =
        options == null || options.getUser() == null
            ? Optional.empty()
            : userRepository.findIdentityId(options.getUser().getId());
    if (current.isEmpty() || owner.isEmpty() || !owner.equals(current)) {
      throw new IllegalStateException(
          "the creation options do not belong to the signed-in identity");
    }
  }

  private void notify(UUID identityId, String label) {
    try {
      notifier.passkeyAdded(identityId, label);
    } catch (RuntimeException e) {
      log.warn(
          "PasskeyAddedNotifier failed for identity {}: {}", identityId, e.getClass().getName());
    }
  }

  private static UUID identityId(Authentication authentication) {
    try {
      return UUID.fromString(authentication.getName());
    } catch (IllegalArgumentException _) {
      throw new IllegalArgumentException("the authentication's name must be an identity id");
    }
  }

  private static Optional<UUID> parseUuid(String value) {
    try {
      return Optional.of(UUID.fromString(value));
    } catch (IllegalArgumentException _) {
      return Optional.empty();
    }
  }

  private static Optional<UUID> credentialId(Authentication authentication) {
    return authentication instanceof WhoAuthenticationToken token
        ? token.credentialId()
        : Optional.empty();
  }
}
