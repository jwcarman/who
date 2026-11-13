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

import java.util.Optional;
import java.util.UUID;

import org.jwcarman.who.core.spi.Credential;
import org.jwcarman.who.core.spi.CredentialRemover;

/**
 * The passkey module's {@link CredentialRemover}. It deletes nothing: deleting an identity deletes
 * its passkeys by foreign key cascade ({@code who_passkey_user} references {@code who_identity},
 * and {@code who_passkey_credential} references {@code who_passkey_user}). It only {@linkplain
 * #find(UUID) describes} a passkey, so {@code WhoService.deleteIdentity} reports its revocation
 * with kind {@code PASSKEY} and the passkey's label. It publishes no events itself, so each
 * revocation is reported once.
 */
public class PasskeyCredentialRemover implements CredentialRemover {

  private final JdbcWhoPasskeyCredentialRepository credentialRepository;

  /**
   * Creates the remover.
   *
   * @param credentialRepository finds passkeys by their credential UUID
   */
  public PasskeyCredentialRemover(JdbcWhoPasskeyCredentialRepository credentialRepository) {
    this.credentialRepository =
        requireNonNull(credentialRepository, "credentialRepository must not be null");
  }

  /**
   * Finds the passkey with the given credential UUID.
   *
   * @param credentialId the credential UUID
   * @return the passkey, with its label, or empty if this module has none with that UUID
   */
  @Override
  public Optional<Credential> find(UUID credentialId) {
    return credentialRepository.findPasskey(credentialId).map(Credential.class::cast);
  }

  /**
   * Deletes nothing: the identity's deletion removes its passkeys by cascade.
   *
   * @param credentialId the credential UUID
   * @return {@code false}, always
   */
  @Override
  public boolean remove(UUID credentialId) {
    return false;
  }

  /**
   * Deletes nothing: passkey rows go with their identity's, so none is left unlinked.
   *
   * @return {@code 0}, always
   */
  @Override
  public int removeUnlinked() {
    return 0;
  }
}
