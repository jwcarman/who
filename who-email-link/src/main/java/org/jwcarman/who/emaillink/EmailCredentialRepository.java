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

import java.util.Optional;
import java.util.UUID;

/** Storage for {@link EmailCredential}s. */
public interface EmailCredentialRepository {

  /**
   * Inserts the credential.
   *
   * @param credential the credential to store
   * @return the stored credential
   * @throws org.springframework.dao.DuplicateKeyException if the address is already registered
   */
  EmailCredential save(EmailCredential credential);

  /**
   * Finds a credential by its already-normalized address.
   *
   * @param normalized the normalized address
   * @return the credential, if any
   */
  Optional<EmailCredential> findByAddress(String normalized);

  /**
   * Finds a credential by id.
   *
   * @param id the credential id
   * @return the credential, if any
   */
  Optional<EmailCredential> findById(UUID id);

  /**
   * Deletes a credential (and, by cascade, its link token).
   *
   * @param id the credential id
   * @return {@code true} if a row was deleted
   */
  boolean deleteById(UUID id);
}
