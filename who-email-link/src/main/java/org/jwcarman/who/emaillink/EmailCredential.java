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

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.jwcarman.who.core.event.CredentialKind;
import org.jwcarman.who.core.id.Identifiers;
import org.jwcarman.who.core.spi.Credential;

/**
 * Credential representing an email address linked to an identity. The address is stored normalized
 * (trimmed and lower-cased).
 *
 * @param id the stable UUID for this credential record
 * @param address the normalized email address
 */
public record EmailCredential(UUID id, String address) implements Credential {

  /** Compact constructor: validates that no field is {@code null}. */
  public EmailCredential {
    requireNonNull(id, "id must not be null");
    requireNonNull(address, "address must not be null");
  }

  /**
   * Returns {@link CredentialKind#EMAIL}.
   *
   * @return {@link CredentialKind#EMAIL}
   */
  @Override
  public CredentialKind kind() {
    return CredentialKind.EMAIL;
  }

  /**
   * Returns the address, which labels the credential in security events.
   *
   * @return the normalized address
   */
  @Override
  public Optional<String> label() {
    return Optional.of(address);
  }

  /**
   * Creates a credential with a new id for the given address.
   *
   * @param address the address; normalized before use
   * @return the new credential
   * @throws IllegalArgumentException if the address is blank or {@code null}
   */
  public static EmailCredential create(String address) {
    return new EmailCredential(Identifiers.uuid(), normalize(address));
  }

  /**
   * Normalizes an address: trims it, then lower-cases it with {@link Locale#ROOT}.
   *
   * @param address the raw address
   * @return the normalized address
   * @throws IllegalArgumentException if the address is blank or {@code null}
   */
  public static String normalize(String address) {
    if (address == null || address.isBlank()) {
      throw new IllegalArgumentException("address must not be blank");
    }
    return address.trim().toLowerCase(Locale.ROOT);
  }
}
