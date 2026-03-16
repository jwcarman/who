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
package org.jwcarman.who.jwt;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

import org.jwcarman.who.core.id.Identifiers;
import org.jwcarman.who.core.spi.Credential;

/**
 * Credential representing a JWT identified by its issuer and subject claims.
 *
 * <p>The {@link #id()} UUID is stable for the lifetime of the credential record and is used as the
 * foreign key to the identity mapping table.
 *
 * @param id stable UUID identifying this credential record
 * @param issuer the JWT {@code iss} claim value
 * @param subject the JWT {@code sub} claim value
 */
public record JwtCredential(UUID id, String issuer, String subject) implements Credential {

  public JwtCredential {
    requireNonNull(id, "id must not be null");
    requireNonNull(issuer, "issuer must not be null");
    requireNonNull(subject, "subject must not be null");
  }

  /**
   * Creates a new {@code JwtCredential} with a randomly generated UUID.
   *
   * @param issuer the JWT {@code iss} claim value
   * @param subject the JWT {@code sub} claim value
   * @return a new credential
   */
  public static JwtCredential create(String issuer, String subject) {
    return new JwtCredential(Identifiers.uuid(), issuer, subject);
  }
}
