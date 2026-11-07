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
import java.util.function.Function;

/**
 * Picks the name the passkey picker shows for an identity (the user entity's {@code name} and
 * {@code display_name}). It is asked each time the identity starts registering a passkey, and who
 * stores its answer.
 */
@FunctionalInterface
public interface PasskeyNameResolver {

  /**
   * Returns the picker name for the identity.
   *
   * @param identityId the identity registering a passkey
   * @param sessionCredentialId the credential the current session signed in with, if known
   * @return the name; never {@code null}
   */
  String nameFor(UUID identityId, Optional<UUID> sessionCredentialId);

  /**
   * A resolver that always answers the identity id, for apps without email credentials.
   *
   * @return the resolver
   */
  static PasskeyNameResolver identityId() {
    return (identityId, sessionCredentialId) -> identityId.toString();
  }

  /**
   * A resolver that answers, in order: the address of the credential the session signed in with;
   * otherwise the identity's oldest address; otherwise the identity id. who-passkey does not depend
   * on who-email-link, so autoconfiguration passes the two lookups (backed by {@code
   * EmailCredentialService.findById} and {@code findOldestFor}).
   *
   * @param credentialAddress the address of a credential, or empty if it is not an email credential
   * @param oldestAddress the address of an identity's oldest email credential, or empty if it has
   *     none
   * @return the resolver
   */
  static PasskeyNameResolver preferring(
      Function<UUID, Optional<String>> credentialAddress,
      Function<UUID, Optional<String>> oldestAddress) {
    requireNonNull(credentialAddress, "credentialAddress must not be null");
    requireNonNull(oldestAddress, "oldestAddress must not be null");
    return (identityId, sessionCredentialId) ->
        sessionCredentialId
            .flatMap(credentialAddress)
            .or(() -> oldestAddress.apply(identityId))
            .orElseGet(identityId::toString);
  }
}
