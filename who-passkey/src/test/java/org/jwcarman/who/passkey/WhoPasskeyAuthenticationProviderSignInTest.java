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
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.spring.security.WhoAuthenticationToken;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.security.web.webauthn.authentication.WebAuthnAuthenticationRequestToken;
import org.springframework.security.web.webauthn.management.ImmutablePublicKeyCredentialCreationOptionsRequest;
import org.springframework.security.web.webauthn.management.ImmutablePublicKeyCredentialRequestOptionsRequest;

/** Sign-in through who's provider with real assertions from an emulated device. */
class WhoPasskeyAuthenticationProviderSignInTest extends AbstractPasskeyTest {

  private static final String ORIGIN = "https://app.test";
  private static final PasskeySettings SETTINGS =
      new PasskeySettings("app.test", "App", Set.of(ORIGIN), Duration.ofMinutes(10));

  @Autowired private JdbcWhoPasskeyUserRepository userRepository;
  @Autowired private JdbcWhoPasskeyCredentialRepository credentialRepository;
  @Autowired private IdentityRepository identityRepository;
  @Autowired private CredentialIdentityRepository credentialIdentityRepository;

  private Identity identity;
  private WhoRelyingPartyOperations operations;
  private WhoPasskeyAuthenticationProvider provider;
  private PasskeyEmulator device;
  private CredentialRecord registered;

  @BeforeEach
  void setUp() {
    identity = identityRepository.save(Identity.create());
    operations =
        new WhoRelyingPartyOperations(
            SETTINGS,
            userRepository,
            credentialRepository,
            PasskeyNameResolver.identityId(),
            (identityId, label) -> {});
    WhoService whoService =
        new WhoService(identityRepository, credentialIdentityRepository, i -> Set.of());
    provider = new WhoPasskeyAuthenticationProvider(operations, credentialRepository, whoService);

    device = PasskeyEmulator.honest(ORIGIN);
    Authentication signedIn =
        new WhoAuthenticationToken(new WhoPrincipal(identity, Set.of()), UUID.randomUUID());
    PublicKeyCredentialCreationOptions creationOptions =
        operations.createPublicKeyCredentialCreationOptions(
            new ImmutablePublicKeyCredentialCreationOptionsRequest(signedIn));
    // Spring's registration filter runs with the caller signed in
    SecurityContextHolder.getContext().setAuthentication(signedIn);
    try {
      registered = operations.registerCredential(device.register(creationOptions, "Laptop"));
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private PublicKeyCredentialRequestOptions requestOptions() {
    return operations.createCredentialRequestOptions(
        new ImmutablePublicKeyCredentialRequestOptionsRequest(null));
  }

  @Test
  void verifiedAssertionSignsInAsTheIdentity() {
    Authentication result =
        provider.authenticate(
            new WebAuthnAuthenticationRequestToken(device.authenticate(requestOptions())));

    WhoAuthenticationToken who = (WhoAuthenticationToken) result;
    assertThat(who.getPrincipal().identity().id()).isEqualTo(identity.id());
    assertThat(who.credentialId())
        .isEqualTo(credentialRepository.credentialUuid(registered.getCredentialId()));
    // verification recorded the use
    assertThat(stored().getSignatureCount()).isGreaterThan(registered.getSignatureCount());
  }

  private CredentialRecord stored() {
    return credentialRepository.findByCredentialId(registered.getCredentialId());
  }

  @Test
  void assertionWithoutUserVerificationIsInvalidCredential() {
    // compare stored values with stored values: PostgreSQL keeps microseconds, the JVM clock may
    // not
    CredentialRecord before = stored();
    WebAuthnAuthenticationRequestToken unverified =
        new WebAuthnAuthenticationRequestToken(
            device.authenticateWithoutUserVerification(requestOptions()));

    assertThatThrownBy(() -> provider.authenticate(unverified))
        .isInstanceOf(BadCredentialsException.class)
        .hasMessage("invalid_credential")
        .hasNoCause();
    // rejected by verification: the relying party never recorded the use
    CredentialRecord after = stored();
    assertThat(after.getSignatureCount()).isEqualTo(before.getSignatureCount());
    assertThat(after.getLastUsed()).isEqualTo(before.getLastUsed());
  }
}
