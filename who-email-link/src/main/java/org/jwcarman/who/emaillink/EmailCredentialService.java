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
package org.jwcarman.who.emaillink;

import static java.util.Objects.requireNonNull;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.event.CredentialAdded;
import org.jwcarman.who.core.event.CredentialRevoked;
import org.jwcarman.who.core.event.OrphanedCredentialsPurged;
import org.jwcarman.who.core.event.WhoEventPublisher;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registers and revokes email credentials, publishing {@link CredentialAdded} and {@link
 * CredentialRevoked} for each change.
 */
public class EmailCredentialService {

  private final EmailCredentialRepository emailCredentialRepository;
  private final CredentialIdentityRepository credentialIdentityRepository;
  private final WhoEventPublisher eventPublisher;
  private final Clock clock;

  /**
   * Creates a new service that publishes no events.
   *
   * @param emailCredentialRepository repository for email credentials
   * @param credentialIdentityRepository repository for linking credentials to identities
   */
  public EmailCredentialService(
      EmailCredentialRepository emailCredentialRepository,
      CredentialIdentityRepository credentialIdentityRepository) {
    this(
        emailCredentialRepository,
        credentialIdentityRepository,
        WhoEventPublisher.NOOP,
        Clock.systemUTC());
  }

  /**
   * Creates a new service.
   *
   * @param emailCredentialRepository repository for email credentials
   * @param credentialIdentityRepository repository for linking credentials to identities
   * @param eventPublisher where credential events are published
   * @param clock stamps the events
   */
  public EmailCredentialService(
      EmailCredentialRepository emailCredentialRepository,
      CredentialIdentityRepository credentialIdentityRepository,
      WhoEventPublisher eventPublisher,
      Clock clock) {
    this.emailCredentialRepository =
        requireNonNull(emailCredentialRepository, "emailCredentialRepository must not be null");
    this.credentialIdentityRepository =
        requireNonNull(
            credentialIdentityRepository, "credentialIdentityRepository must not be null");
    this.eventPublisher = requireNonNull(eventPublisher, "eventPublisher must not be null");
    this.clock = requireNonNull(clock, "clock must not be null");
  }

  /**
   * Registers an email address for the identity and links the new credential to it.
   *
   * <p>Call this only with an address the caller has verified; who does not verify ownership here.
   *
   * <p>If the address is already registered but linked to no identity (its identity was deleted
   * without {@code WhoService.deleteIdentity}), that orphaned credential is deleted, with any
   * outstanding sign-in link, and a new credential is registered in its place. If the address is
   * already this identity's, its credential is returned unchanged.
   *
   * <p>Publishes {@link CredentialAdded} for a new credential. An orphaned credential it replaces
   * belongs to no identity, so its deletion is published first as {@link OrphanedCredentialsPurged}
   * with a count of one. Events carry the address only if it is safe to pass on (see {@link
   * EmailCredential#label()}).
   *
   * @param identity the identity to link the credential to
   * @param address the address; normalized before use
   * @return the identity's credential for the address
   * @throws EmailAddressTakenException if the address is registered to another identity
   */
  @Transactional
  public EmailCredential register(Identity identity, String address) {
    requireNonNull(identity, "identity must not be null");
    EmailCredential credential = EmailCredential.create(address);
    // Check first so the common case does not abort a caller's PostgreSQL transaction; the catch
    // below covers a concurrent registration.
    Optional<EmailCredential> existing =
        emailCredentialRepository.findByAddress(credential.address());
    if (existing.isPresent()) {
      Optional<UUID> owner =
          credentialIdentityRepository.findIdentityIdByCredentialId(existing.get().id());
      if (owner.isPresent()) {
        if (owner.get().equals(identity.id())) {
          return existing.get();
        }
        throw new EmailAddressTakenException();
      }
      // Orphaned. Replace it rather than relink it: deleting the row cascades to its link token, so
      // a link sent to the deleted identity cannot sign in the new one.
      if (emailCredentialRepository.deleteById(existing.get().id())) {
        eventPublisher.publish(new OrphanedCredentialsPurged(clock.instant(), 1));
      }
    }
    try {
      emailCredentialRepository.save(credential);
    } catch (DuplicateKeyException _) {
      throw new EmailAddressTakenException();
    }
    credentialIdentityRepository.link(credential.id(), identity.id());
    eventPublisher.publish(
        new CredentialAdded(
            clock.instant(),
            identity.id(),
            credential.id(),
            credential.kind(),
            credential.label().orElse(null)));
    return credential;
  }

  /**
   * Finds a credential by address, normalizing the address first.
   *
   * @param address the raw address
   * @return the credential, if any
   */
  public Optional<EmailCredential> findByAddress(String address) {
    return emailCredentialRepository.findByAddress(EmailCredential.normalize(address));
  }

  /**
   * Finds a credential by id.
   *
   * @param credentialId the credential id
   * @return the credential, if any
   */
  public Optional<EmailCredential> findById(UUID credentialId) {
    requireNonNull(credentialId, "credentialId must not be null");
    return emailCredentialRepository.findById(credentialId);
  }

  /**
   * Finds the identity's oldest email credential: the smallest (UUIDv7, so earliest) id among the
   * email credentials linked to it.
   *
   * @param identityId the identity
   * @return the oldest credential, or empty if the identity has no email credential
   */
  public Optional<EmailCredential> findOldestFor(UUID identityId) {
    requireNonNull(identityId, "identityId must not be null");
    return emailCredentialRepository.findOldestByIdentityId(identityId);
  }

  /**
   * Revokes an email credential: deletes it (and its token) and unlinks it from its identity, then
   * publishes {@link CredentialRevoked} if it was linked to one. Does nothing for an unknown id.
   *
   * @param credentialId the credential id
   */
  @Transactional
  public void revoke(UUID credentialId) {
    requireNonNull(credentialId, "credentialId must not be null");
    Optional<EmailCredential> credential = emailCredentialRepository.findById(credentialId);
    if (credential.isEmpty()) {
      return;
    }
    // looked up before unlinking, which removes the only record of the owner
    Optional<UUID> owner = credentialIdentityRepository.findIdentityIdByCredentialId(credentialId);
    if (emailCredentialRepository.deleteById(credentialId)) {
      credentialIdentityRepository.unlink(credentialId);
      owner.ifPresent(
          identityId ->
              eventPublisher.publish(
                  new CredentialRevoked(
                      clock.instant(),
                      identityId,
                      credentialId,
                      credential.get().kind(),
                      credential.get().label().orElse(null))));
    }
  }
}
