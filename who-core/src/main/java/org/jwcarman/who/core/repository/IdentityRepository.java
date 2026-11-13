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
package org.jwcarman.who.core.repository;

import java.util.Optional;
import java.util.UUID;

import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.IdentityStatus;

/** Repository interface for {@link Identity} persistence. */
public interface IdentityRepository {

  /**
   * Finds an identity by its UUID.
   *
   * @param id the identity UUID
   * @return the identity, or empty if not found
   */
  Optional<Identity> findById(UUID id);

  /**
   * Persists the given identity, inserting or updating as necessary.
   *
   * @param identity the identity to save
   * @return the saved identity
   */
  Identity save(Identity identity);

  /**
   * Returns whether an identity with the given UUID exists.
   *
   * @param id the identity UUID
   * @return true if an identity exists with that UUID
   */
  boolean existsById(UUID id);

  /**
   * Deletes the identity with the given UUID.
   *
   * @param id the identity UUID
   */
  void deleteById(UUID id);

  /**
   * Sets the status of the identity with the given UUID, reporting whether anything changed. The
   * default finds then saves; implementations backed by a database should override it with one
   * conditional update, so that concurrent callers cannot both see a change.
   *
   * @param id the identity UUID
   * @param status the new status
   * @return {@code true} only if the identity exists and its status was different
   */
  default boolean changeStatus(UUID id, IdentityStatus status) {
    return findById(id)
        .filter(identity -> identity.status() != status)
        .map(identity -> save(identity.withStatus(status)))
        .isPresent();
  }
}
