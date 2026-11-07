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

import java.util.Optional;
import java.util.UUID;

import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

/** Registers and revokes email credentials. */
public class EmailCredentialService {

  private final EmailCredentialRepository emailCredentialRepository;
  private final CredentialIdentityRepository credentialIdentityRepository;

  /**
   * Creates a new service.
   *
   * @param emailCredentialRepository repository for email credentials
   * @param credentialIdentityRepository repository for linking credentials to identities
   */
  public EmailCredentialService(
      EmailCredentialRepository emailCredentialRepository,
      CredentialIdentityRepository credentialIdentityRepository) {
    this.emailCredentialRepository =
        requireNonNull(emailCredentialRepository, "emailCredentialRepository must not be null");
    this.credentialIdentityRepository =
        requireNonNull(
            credentialIdentityRepository, "credentialIdentityRepository must not be null");
  }

  /**
   * Registers an email address for the identity and links the credential to it.
   *
   * <p>Call this only with an address the caller has verified; who does not verify ownership here.
   *
   * <p>If the address is already registered but linked to no identity (its identity was deleted
   * without {@code WhoService.deleteIdentity}), that credential is linked to this identity and
   * returned. If it is already linked to this identity, it is returned unchanged.
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
    // Check first so the common case does not abort a caller's PostgreSQL transaction; the catches
    // below cover a concurrent registration.
    Optional<EmailCredential> existing =
        emailCredentialRepository.findByAddress(credential.address());
    if (existing.isPresent()) {
      return claim(existing.get(), identity);
    }
    try {
      emailCredentialRepository.save(credential);
    } catch (DuplicateKeyException _) {
      throw new EmailAddressTakenException();
    }
    credentialIdentityRepository.link(credential.id(), identity.id());
    return credential;
  }

  private EmailCredential claim(EmailCredential existing, Identity identity) {
    Optional<UUID> owner = credentialIdentityRepository.findIdentityIdByCredentialId(existing.id());
    if (owner.isPresent()) {
      if (owner.get().equals(identity.id())) {
        return existing;
      }
      throw new EmailAddressTakenException();
    }
    try {
      credentialIdentityRepository.link(existing.id(), identity.id());
    } catch (DuplicateKeyException _) {
      throw new EmailAddressTakenException();
    }
    return existing;
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
   * Revokes an email credential: deletes it (and its token) and unlinks it from its identity.
   *
   * @param credentialId the credential id
   */
  @Transactional
  public void revoke(UUID credentialId) {
    requireNonNull(credentialId, "credentialId must not be null");
    if (emailCredentialRepository.deleteById(credentialId)) {
      credentialIdentityRepository.unlink(credentialId);
    }
  }
}
