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
package org.jwcarman.who.core.event;

import java.time.Instant;

/**
 * A security-relevant occurrence inside who, published through {@link WhoEventPublisher}.
 *
 * <p>Compatibility policy: new event types and new enum values may appear in minor releases.
 * Listeners must tolerate unknown values and ignore event types they do not recognise. Event
 * records may also gain components in minor releases, so read fields through their accessors: do
 * not construct who's events or match them with record deconstruction patterns.
 */
public interface WhoEvent {

  /**
   * Returns when the event occurred.
   *
   * @return the event timestamp
   */
  Instant occurredAt();
}
