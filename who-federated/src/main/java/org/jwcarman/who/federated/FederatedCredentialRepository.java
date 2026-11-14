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

import java.util.Optional;
import java.util.UUID;

/** Repository for persisting and retrieving {@link FederatedCredential} records. */
public interface FederatedCredentialRepository {

  /**
   * Finds a credential by its issuer and subject claims.
   *
   * @param issuer the issuer
   * @param subject the subject at that issuer
   * @return the credential, or empty if not found
   */
  Optional<FederatedCredential> findByIssuerAndSubject(String issuer, String subject);

  /**
   * Finds a credential by its id, so that unlinking it can report its issuer. A repository that
   * cannot find by id keeps this default, and unlinks it serves report no issuer.
   *
   * @param id the credential UUID
   * @return the matching credential, or empty if not found
   */
  default Optional<FederatedCredential> findById(UUID id) {
    return Optional.empty();
  }

  /**
   * Persists a credential. If a record with the same id already exists it is left unchanged
   * (federated credentials are immutable — issuer and subject never change after creation).
   *
   * @param credential the credential to save
   * @return the saved credential
   */
  FederatedCredential save(FederatedCredential credential);

  /**
   * Deletes the credential with the given id.
   *
   * @param id the credential UUID
   */
  void deleteById(UUID id);
}
