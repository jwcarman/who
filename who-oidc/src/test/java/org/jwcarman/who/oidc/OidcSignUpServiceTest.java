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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.event.WhoEventPublisher;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.core.service.Resolution;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.core.signup.SignUpDecision;
import org.jwcarman.who.federated.FederatedCredential;
import org.jwcarman.who.federated.FederatedCredentialRepository;
import org.jwcarman.who.federated.FederatedCredentialService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * What the database hides: a link that collides with another link of the same account, which two
 * real requests reach only by chance, and the guards that hold where an app's own repositories have
 * no unique keys. The end-to-end tests in who-autoconfigure cover the rest.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class OidcSignUpServiceTest {

  private static final String ISSUER = "https://idp.example.com";

  private final WhoService whoService = mock(WhoService.class);
  private final IdentityRepository identities = mock(IdentityRepository.class);
  private final FederatedCredentialRepository credentials =
      mock(FederatedCredentialRepository.class);
  private final FederatedCredentialService credentialService =
      mock(FederatedCredentialService.class);
  private final CredentialIdentityRepository credentialIdentities =
      mock(CredentialIdentityRepository.class);
  private final Identity identity = Identity.create();
  private final PendingSignUp pending =
      new PendingSignUp(
          "idp",
          ISSUER,
          "alice",
          "alice",
          null,
          Map.of(),
          identity.id(),
          Instant.now().plus(Duration.ofMinutes(10)));

  private final OidcSignUpService service = service();

  private OidcSignUpService service() {
    PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    when(transactions.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
    return new OidcSignUpService(
        request -> SignUpDecision.allow(),
        whoService,
        identities,
        credentials,
        credentialService,
        credentialIdentities,
        WhoEventPublisher.NOOP,
        Clock.systemUTC(),
        transactions);
  }

  /** The account is new when the link looks, and another link saves it first. */
  private FederatedCredential lostTheRace(UUID winner) {
    FederatedCredential saved = FederatedCredential.create(ISSUER, "alice");
    when(credentials.findByIssuerAndSubject(ISSUER, "alice"))
        .thenReturn(Optional.empty(), Optional.of(saved));
    when(credentialService.link(identity, ISSUER, "alice"))
        .thenThrow(new DuplicateKeyException("who_federated_credential_issuer_subject"));
    when(credentialIdentities.findIdentityIdByCredentialId(saved.id()))
        .thenReturn(Optional.of(winner));
    return saved;
  }

  @Test
  void a_link_that_loses_to_another_identity_is_already_linked() {
    lostTheRace(UUID.randomUUID());

    assertThatThrownBy(() -> service.link(identity, pending))
        .isInstanceOf(AlreadyLinkedException.class);
  }

  @Test
  void a_link_that_loses_to_the_same_identity_is_that_link() {
    FederatedCredential saved = lostTheRace(identity.id());

    assertThat(service.link(identity, pending)).isEqualTo(saved);
  }

  @Test
  void a_sign_up_of_an_account_an_inactive_identity_holds_is_refused() {
    UUID inactive = UUID.randomUUID();
    FederatedCredential held = FederatedCredential.create(ISSUER, "alice");
    when(credentials.findByIssuerAndSubject(ISSUER, "alice")).thenReturn(Optional.of(held));
    when(whoService.resolveDetailed(held)).thenReturn(new Resolution.Inactive(inactive));

    assertThatThrownBy(() -> service.signUp(pending)).isInstanceOf(SignUpRefusedException.class);
    verify(identities, never()).save(any());
    verify(credentialIdentities, never()).link(any(), any());
  }
}
