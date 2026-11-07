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
package org.jwcarman.who.core.spi;

import java.util.UUID;

/**
 * Deletes one credential module's credential rows. Credential rows do not reference the identity
 * they belong to; only their link does. {@code WhoService} calls every remover when it deletes an
 * identity, so the identity's credentials go with it, and when it purges credentials that are
 * linked to no identity.
 *
 * <p>Each credential module that stores its own credential rows provides one. Passkeys need none:
 * their rows are removed by cascade when their identity is deleted.
 */
public interface CredentialRemover {

  /**
   * Deletes this module's credential with the given id, if this module owns it. Does not touch the
   * credential's link.
   *
   * @param credentialId the credential id
   * @return {@code true} if a credential row was deleted, {@code false} if this module has none
   *     with that id
   */
  boolean remove(UUID credentialId);

  /**
   * Deletes this module's credentials that are linked to no identity.
   *
   * @return the number of credentials deleted
   */
  int removeUnlinked();
}
