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

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.UUID;

import org.springframework.transaction.annotation.Transactional;

/** Lists and revokes an identity's passkeys. */
public class PasskeyService {

  private final JdbcWhoPasskeyCredentialRepository credentialRepository;

  /**
   * Creates a new service.
   *
   * @param credentialRepository the passkey credential repository
   */
  public PasskeyService(JdbcWhoPasskeyCredentialRepository credentialRepository) {
    this.credentialRepository =
        requireNonNull(credentialRepository, "credentialRepository must not be null");
  }

  /**
   * Revokes a passkey: deletes its credential record and unlinks it from its identity. Does nothing
   * if no passkey has the UUID.
   *
   * @param credentialUuid the passkey's credential UUID
   */
  @Transactional
  public void revoke(UUID credentialUuid) {
    requireNonNull(credentialUuid, "credentialUuid must not be null");
    credentialRepository.findCredentialId(credentialUuid).ifPresent(credentialRepository::delete);
  }

  /**
   * Lists an identity's passkeys, oldest first.
   *
   * @param identityId the identity
   * @return the identity's passkeys
   */
  public List<PasskeySummary> list(UUID identityId) {
    return credentialRepository.findSummariesByIdentityId(identityId);
  }
}
