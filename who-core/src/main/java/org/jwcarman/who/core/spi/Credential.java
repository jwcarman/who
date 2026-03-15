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
 * Marker interface for credential types. Each credential implementation (e.g. JWT, API key)
 * provides a stable UUID that can be linked to an {@link org.jwcarman.who.core.domain.Identity}.
 */
public interface Credential {

  /**
   * Returns the unique identifier for this credential.
   *
   * @return the credential UUID
   */
  UUID id();
}
