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
package org.jwcarman.who.core.service;

import static java.util.Objects.requireNonNull;

import java.util.Optional;
import java.util.UUID;

import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.spi.Credential;

/**
 * The outcome of {@link WhoService#resolveDetailed(Credential)}: either the resolved principal or
 * the reason the credential did not resolve.
 */
public sealed interface Resolution
    permits Resolution.Resolved, Resolution.NotLinked, Resolution.NoIdentity, Resolution.Inactive {

  /**
   * Returns the resolved principal, if any.
   *
   * @return the principal when {@link Resolved}, otherwise empty
   */
  default Optional<WhoPrincipal> principal() {
    return Optional.empty();
  }

  /**
   * The credential resolved to an active identity.
   *
   * @param value the resolved principal; named {@code value} because {@link #principal()} is the
   *     {@link Optional} view shared by every outcome
   */
  record Resolved(WhoPrincipal value) implements Resolution {

    /** Validates that the principal is not null. */
    public Resolved {
      requireNonNull(value, "value must not be null");
    }

    @Override
    public Optional<WhoPrincipal> principal() {
      return Optional.of(value);
    }
  }

  /** The credential is not linked to any identity. */
  record NotLinked() implements Resolution {}

  /**
   * The credential is linked to an identity that does not exist.
   *
   * @param identityId the identity the credential is linked to
   */
  record NoIdentity(UUID identityId) implements Resolution {

    /** Validates that the identity id is not null. */
    public NoIdentity {
      requireNonNull(identityId, "identityId must not be null");
    }
  }

  /**
   * The credential is linked to an identity that is not active.
   *
   * @param identityId the inactive identity
   */
  record Inactive(UUID identityId) implements Resolution {

    /** Validates that the identity id is not null. */
    public Inactive {
      requireNonNull(identityId, "identityId must not be null");
    }
  }
}
