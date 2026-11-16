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

import java.util.Optional;
import java.util.UUID;

import org.jwcarman.who.core.event.CredentialKind;
import org.jwcarman.who.core.id.Identifiers;
import org.jwcarman.who.core.spi.Credential;

/**
 * An account at an identity provider or token issuer, identified by its issuer and subject: the
 * credential both JWT sign-in and identity-provider sign-in key on.
 *
 * <p>The {@link #id()} UUID is stable for the lifetime of the credential record and is used as the
 * foreign key to the identity mapping table.
 *
 * @param id stable UUID identifying this credential record
 * @param issuer the issuer, as who's configuration names it
 * @param subject the account's subject at that issuer
 */
public record FederatedCredential(UUID id, String issuer, String subject) implements Credential {

  /** Validates that no field is null. */
  public FederatedCredential {
    requireNonNull(id, "id must not be null");
    requireNonNull(issuer, "issuer must not be null");
    requireNonNull(subject, "subject must not be null");
  }

  /**
   * Creates a new {@code FederatedCredential} with a randomly generated UUID.
   *
   * @param issuer the issuer
   * @param subject the subject at that issuer
   * @return a new credential
   */
  public static FederatedCredential create(String issuer, String subject) {
    return new FederatedCredential(Identifiers.uuid(), issuer, subject);
  }

  /**
   * Returns {@link CredentialKind#FEDERATED}.
   *
   * @return {@link CredentialKind#FEDERATED}
   */
  @Override
  public CredentialKind kind() {
    return CredentialKind.FEDERATED;
  }

  /**
   * Returns the {@link #issuer()}. The subject is not part of the label.
   *
   * @return the issuer
   */
  @Override
  public Optional<String> label() {
    return Optional.of(issuer);
  }
}
