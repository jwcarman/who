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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.spring.security.WhoAuthenticationToken;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.security.web.webauthn.api.ResidentKeyRequirement;
import org.springframework.security.web.webauthn.api.UserVerificationRequirement;
import org.springframework.security.web.webauthn.management.ImmutablePublicKeyCredentialCreationOptionsRequest;
import org.springframework.security.web.webauthn.management.ImmutablePublicKeyCredentialRequestOptionsRequest;

import com.webauthn4j.verifier.exception.UserNotVerifiedException;

class WhoRelyingPartyOperationsTest extends AbstractPasskeyTest {

  private static final String ORIGIN = "https://app.test";
  private static final PasskeySettings SETTINGS =
      new PasskeySettings("app.test", "App", Set.of(ORIGIN), Duration.ofMinutes(10));

  @Autowired private JdbcWhoPasskeyUserRepository userRepository;
  @Autowired private JdbcWhoPasskeyCredentialRepository credentialRepository;
  @Autowired private IdentityRepository identityRepository;

  private final List<String> notifications = new ArrayList<>();
  private final List<Optional<UUID>> resolvedSessionCredentials = new ArrayList<>();
  private Identity identity;
  private UUID sessionCredentialId;
  private WhoRelyingPartyOperations operations;

  @BeforeEach
  void setUp() {
    identity = identityRepository.save(Identity.create());
    sessionCredentialId = UUID.randomUUID();
    operations = operations((identityId, label) -> notifications.add(identityId + ":" + label));
  }

  private WhoRelyingPartyOperations operations(PasskeyAddedNotifier notifier) {
    PasskeyNameResolver names =
        (identityId, sessionCredential) -> {
          resolvedSessionCredentials.add(sessionCredential);
          return "owner@example.com";
        };
    return new WhoRelyingPartyOperations(
        SETTINGS, userRepository, credentialRepository, names, notifier);
  }

  private Authentication signedIn() {
    return new WhoAuthenticationToken(new WhoPrincipal(identity, Set.of()), sessionCredentialId);
  }

  private PublicKeyCredentialCreationOptions creationOptions() {
    return operations.createPublicKeyCredentialCreationOptions(
        new ImmutablePublicKeyCredentialCreationOptionsRequest(signedIn()));
  }

  @Test
  void creationOptionsRequireUserVerificationAndResidentKey() {
    PublicKeyCredentialCreationOptions options = creationOptions();

    assertThat(options.getAuthenticatorSelection().getUserVerification())
        .isEqualTo(UserVerificationRequirement.REQUIRED);
    assertThat(options.getAuthenticatorSelection().getResidentKey())
        .isEqualTo(ResidentKeyRequirement.REQUIRED);
    assertThat(options.getRp().getId()).isEqualTo("app.test");
    assertThat(options.getRp().getName()).isEqualTo("App");
  }

  @Test
  void creationOptionsCarryWhosUserEntity() {
    PublicKeyCredentialCreationOptions options = creationOptions();

    assertThat(options.getUser().getId().getBytes()).hasSize(64);
    assertThat(options.getUser().getName()).isEqualTo("owner@example.com");
    assertThat(options.getUser().getDisplayName()).isEqualTo("owner@example.com");
    assertThat(userRepository.findIdentityId(options.getUser().getId())).contains(identity.id());
    assertThat(resolvedSessionCredentials).containsExactly(Optional.of(sessionCredentialId));
  }

  @Test
  void creationOptionsRequireAnAuthenticatedUser() {
    Authentication anonymous =
        new AnonymousAuthenticationToken(
            "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

    assertThatThrownBy(
            () ->
                operations.createPublicKeyCredentialCreationOptions(
                    new ImmutablePublicKeyCredentialCreationOptionsRequest(anonymous)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(resolvedSessionCredentials).isEmpty();
  }

  @Test
  void requestOptionsRequireUserVerification() {
    PublicKeyCredentialRequestOptions options =
        operations.createCredentialRequestOptions(
            new ImmutablePublicKeyCredentialRequestOptionsRequest(null));

    assertThat(options.getUserVerification()).isEqualTo(UserVerificationRequirement.REQUIRED);
    assertThat(options.getRpId()).isEqualTo("app.test");
  }

  @Test
  void registrationSucceedsWithUserVerification() {
    PublicKeyCredentialCreationOptions options = creationOptions();

    CredentialRecord record =
        operations.registerCredential(PasskeyEmulator.honest(ORIGIN).register(options, "Laptop"));

    assertThat(record.isUvInitialized()).isTrue();
    assertThat(record.getUserEntityUserId()).isEqualTo(options.getUser().getId());
    assertThat(credentialRepository.findByCredentialId(record.getCredentialId())).isNotNull();
  }

  @Test
  void registrationWithoutUserVerificationFails() {
    PublicKeyCredentialCreationOptions options = creationOptions();
    var request = PasskeyEmulator.withoutUserVerification(ORIGIN).register(options, "Laptop");

    assertThatThrownBy(() -> operations.registerCredential(request))
        .isInstanceOf(UserNotVerifiedException.class);
    assertThat(credentialRepository.findByUserId(options.getUser().getId())).isEmpty();
    assertThat(notifications).isEmpty();
  }

  @Test
  void secondRegistrationReusesTheUserHandle() {
    PublicKeyCredentialCreationOptions first = creationOptions();
    CredentialRecord laptop =
        operations.registerCredential(PasskeyEmulator.honest(ORIGIN).register(first, "Laptop"));

    PublicKeyCredentialCreationOptions second = creationOptions();
    CredentialRecord phone =
        operations.registerCredential(PasskeyEmulator.honest(ORIGIN).register(second, "Phone"));

    assertThat(second.getUser().getId()).isEqualTo(first.getUser().getId());
    assertThat(second.getExcludeCredentials())
        .extracting(d -> d.getId())
        .containsExactly(laptop.getCredentialId());
    assertThat(phone.getUserEntityUserId()).isEqualTo(laptop.getUserEntityUserId());
    assertThat(credentialRepository.findByUserId(first.getUser().getId())).hasSize(2);
  }

  @Test
  void notifierCalledOnceAfterSuccessfulRegistration() {
    PublicKeyCredentialCreationOptions options = creationOptions();
    assertThat(notifications).isEmpty();

    operations.registerCredential(PasskeyEmulator.honest(ORIGIN).register(options, "Laptop"));

    assertThat(notifications).containsExactly(identity.id() + ":Laptop");
  }

  @Test
  void notifierFailureDoesNotFailRegistration() {
    WhoRelyingPartyOperations failing =
        operations(
            (identityId, label) -> {
              throw new IllegalStateException("mail is down");
            });
    PublicKeyCredentialCreationOptions options =
        failing.createPublicKeyCredentialCreationOptions(
            new ImmutablePublicKeyCredentialCreationOptionsRequest(signedIn()));

    CredentialRecord record =
        failing.registerCredential(PasskeyEmulator.honest(ORIGIN).register(options, "Laptop"));

    assertThat(credentialRepository.findByCredentialId(record.getCredentialId())).isNotNull();
  }
}
