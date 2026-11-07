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
   * Registers an email address for the identity and links the new credential to it.
   *
   * <p>Call this only with an address the caller has verified; who does not verify ownership here.
   *
   * @param identity the identity to link the credential to
   * @param address the address; normalized before use
   * @return the new credential
   * @throws EmailAddressTakenException if the address is already registered
   */
  @Transactional
  public EmailCredential register(Identity identity, String address) {
    requireNonNull(identity, "identity must not be null");
    EmailCredential credential = EmailCredential.create(address);
    // Check first so the common case does not abort a caller's PostgreSQL transaction; the catch
    // below covers a concurrent registration.
    if (emailCredentialRepository.findByAddress(credential.address()).isPresent()) {
      throw new EmailAddressTakenException();
    }
    try {
      emailCredentialRepository.save(credential);
    } catch (DuplicateKeyException _) {
      throw new EmailAddressTakenException();
    }
    credentialIdentityRepository.link(credential.id(), identity.id());
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
