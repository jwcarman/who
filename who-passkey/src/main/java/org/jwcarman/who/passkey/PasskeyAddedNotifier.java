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

import java.util.UUID;

/**
 * Told after a passkey is registered, so the owner can notice a passkey they did not add. Called
 * only after a successful registration; an exception it throws is logged and does not fail the
 * registration.
 */
@FunctionalInterface
public interface PasskeyAddedNotifier {

  /**
   * A passkey was added to the identity.
   *
   * @param identityId the identity the passkey belongs to
   * @param label the passkey's label, as the user named it
   */
  void passkeyAdded(UUID identityId, String label);
}
