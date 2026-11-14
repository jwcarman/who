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

/**
 * Publishes {@link WhoEvent}s to whatever listener mechanism the host application uses.
 *
 * <p>Implementations must not throw: who publishes from inside its own operations (sign-in
 * handlers, transactional changes), so an exception from a publisher can change who's responses or
 * fail the surrounding operation. Implementations should also defer delivery until after the
 * surrounding transaction commits, so a listener never sees a change that is later rolled back.
 * who's Spring publisher does both; a replacement that does not can change who's behavior.
 */
@FunctionalInterface
public interface WhoEventPublisher {

  /** A publisher that discards every event. */
  WhoEventPublisher NOOP = event -> {};

  /**
   * Publishes an event.
   *
   * @param event the event to publish
   */
  void publish(WhoEvent event);
}
