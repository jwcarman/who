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
package org.jwcarman.who.federated;

import static java.util.Objects.requireNonNull;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.event.CredentialAdded;
import org.jwcarman.who.core.event.CredentialKind;
import org.jwcarman.who.core.event.CredentialRevoked;
import org.jwcarman.who.core.event.WhoEventPublisher;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for linking issuer/subject pairs to identities and unlinking them again.
 *
 * <p>Linking publishes {@link CredentialAdded} and unlinking publishes {@link CredentialRevoked},
 * each labelled with the issuer. Neither event carries the subject.
 */
public class FederatedCredentialService {

  private final FederatedCredentialRepository federatedCredentialRepository;
  private final CredentialIdentityRepository credentialIdentityRepository;
  private final WhoEventPublisher eventPublisher;
  private final Clock clock;

  /**
   * Creates a new {@code FederatedCredentialService}.
   *
   * @param federatedCredentialRepository repository for federated credentials
   * @param credentialIdentityRepository repository for linking credentials to identities
   * @param eventPublisher where {@link CredentialAdded} and {@link CredentialRevoked} are published
   * @param clock stamps the events
   */
  public FederatedCredentialService(
      FederatedCredentialRepository federatedCredentialRepository,
      CredentialIdentityRepository credentialIdentityRepository,
      WhoEventPublisher eventPublisher,
      Clock clock) {
    this.federatedCredentialRepository =
        requireNonNull(
            federatedCredentialRepository, "federatedCredentialRepository must not be null");
    this.credentialIdentityRepository =
        requireNonNull(
            credentialIdentityRepository, "credentialIdentityRepository must not be null");
    this.eventPublisher = requireNonNull(eventPublisher, "eventPublisher must not be null");
    this.clock = requireNonNull(clock, "clock must not be null");
  }

  /**
   * Saves a credential for the issuer/subject pair and links it to the identity, then publishes
   * {@link CredentialAdded} labelled with the issuer.
   *
   * @param identity the identity to link the credential to
   * @param issuer the issuer
   * @param subject the subject at that issuer
   * @return the saved credential
   */
  @Transactional
  public FederatedCredential link(Identity identity, String issuer, String subject) {
    requireNonNull(identity, "identity must not be null");
    requireNonNull(issuer, "issuer must not be null");
    requireNonNull(subject, "subject must not be null");

    FederatedCredential credential =
        federatedCredentialRepository.save(FederatedCredential.create(issuer, subject));
    credentialIdentityRepository.link(credential.id(), identity.id());
    eventPublisher.publish(
        new CredentialAdded(
            clock.instant(), identity.id(), credential.id(), credential.kind(), issuer));
    return credential;
  }

  /**
   * Links a credential that is already saved, and linked to no identity, to the identity, then
   * publishes {@link CredentialAdded} labelled with the issuer.
   *
   * @param identity the identity to link the credential to
   * @param credential the saved credential
   * @return the credential
   */
  @Transactional
  public FederatedCredential linkExisting(Identity identity, FederatedCredential credential) {
    requireNonNull(identity, "identity must not be null");
    requireNonNull(credential, "credential must not be null");

    credentialIdentityRepository.link(credential.id(), identity.id());
    eventPublisher.publish(
        new CredentialAdded(
            clock.instant(),
            identity.id(),
            credential.id(),
            credential.kind(),
            credential.issuer()));
    return credential;
  }

  /**
   * Deletes the credential and its identity link, so the pair stops signing in, then publishes
   * {@link CredentialRevoked} if it was linked to an identity. The label is the issuer, or {@code
   * null} when the repository cannot find the credential by id. Does nothing visible for an id that
   * is not linked.
   *
   * @param credentialId the id of the federated credential
   */
  @Transactional
  public void unlink(UUID credentialId) {
    requireNonNull(credentialId, "credentialId must not be null");
    // looked up before deleting and unlinking, which remove the only record of issuer and owner
    Optional<String> issuer =
        federatedCredentialRepository.findById(credentialId).map(FederatedCredential::issuer);
    Optional<UUID> owner = credentialIdentityRepository.findIdentityIdByCredentialId(credentialId);
    federatedCredentialRepository.deleteById(credentialId);
    credentialIdentityRepository.unlink(credentialId);
    owner.ifPresent(
        identityId ->
            eventPublisher.publish(
                new CredentialRevoked(
                    clock.instant(),
                    identityId,
                    credentialId,
                    CredentialKind.FEDERATED,
                    issuer.orElse(null))));
  }
}
