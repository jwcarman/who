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
package org.jwcarman.who.autoconfigure;

import org.jwcarman.who.core.event.WhoEvent;
import org.jwcarman.who.core.event.WhoEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Publishes who's events as Spring application events. Inside a transaction an event is published
 * only after that transaction commits, so listeners never see a change that is rolled back; with no
 * transaction it is published at once. A failing listener is logged, by class names only, and never
 * disturbs the code that caused the event.
 */
public final class SpringWhoEventPublisher implements WhoEventPublisher {

  private static final Logger log = LoggerFactory.getLogger(SpringWhoEventPublisher.class);

  private final ApplicationEventPublisher publisher;

  /**
   * Creates a publisher.
   *
   * @param publisher the Spring publisher to forward events to
   */
  public SpringWhoEventPublisher(ApplicationEventPublisher publisher) {
    this.publisher = publisher;
  }

  @Override
  public void publish(WhoEvent event) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              deliver(event);
            }
          });
    } else {
      deliver(event);
    }
  }

  private void deliver(WhoEvent event) {
    try {
      publisher.publishEvent(event);
    } catch (RuntimeException e) {
      // Class names only: the exception message could carry personal data.
      log.warn(
          "A listener failed on {}: {}", event.getClass().getSimpleName(), e.getClass().getName());
    }
  }
}
