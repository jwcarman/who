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
package org.jwcarman.who.nativetests.emaillink;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jwcarman.who.core.event.CredentialAdded;
import org.jwcarman.who.core.event.SignInFailed;
import org.jwcarman.who.core.event.SignInSucceeded;
import org.jwcarman.who.core.event.SignedOut;
import org.jwcarman.who.core.event.WhoEvent;
import org.jwcarman.who.emaillink.SignInEmailRequested;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Records who's events as maps of strings, written out by hand so that nothing about who's event
 * types is read reflectively. An event it does not know is still recorded, by its type alone.
 */
@Component
class EventRecorder {

  private final List<Map<String, String>> events = new ArrayList<>();

  @EventListener
  void on(WhoEvent event) {
    Map<String, String> entry = new LinkedHashMap<>();
    entry.put("type", event.getClass().getSimpleName());
    switch (event) {
      case SignInSucceeded e -> {
        entry.put("method", e.method().name());
        put(entry, "identityId", e.identityId());
      }
      case SignInFailed e -> {
        entry.put("method", e.method().name());
        entry.put("reason", e.reason().name());
        put(entry, "identityId", e.identityId());
      }
      case SignInEmailRequested e -> {
        entry.put("outcome", e.outcome().name());
        put(entry, "identityId", e.identityId());
      }
      case CredentialAdded e -> {
        entry.put("kind", e.kind().name());
        put(entry, "identityId", e.identityId());
      }
      case SignedOut e -> put(entry, "identityId", e.identityId());
      default -> {
        // recorded by type only
      }
    }
    synchronized (events) {
      events.add(entry);
    }
  }

  List<Map<String, String>> events() {
    synchronized (events) {
      return List.copyOf(events);
    }
  }

  void clear() {
    synchronized (events) {
      events.clear();
    }
  }

  private static void put(Map<String, String> entry, String key, UUID value) {
    if (value != null) {
      entry.put(key, value.toString());
    }
  }
}
