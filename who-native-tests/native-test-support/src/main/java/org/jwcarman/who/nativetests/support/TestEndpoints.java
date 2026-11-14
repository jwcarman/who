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
package org.jwcarman.who.nativetests.support;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;

/**
 * The test apps' own endpoints: {@code /test/events}, the who events the app has seen, each as a
 * map of strings with at least {@code type}; and {@code /test/mail}, the last email sent to an
 * address.
 */
public final class TestEndpoints {

  static final Duration WAIT = Duration.ofSeconds(10);

  private TestEndpoints() {}

  /**
   * The events the app has recorded, oldest first.
   *
   * @param client a client of the app
   * @return the events
   */
  public static List<Map<String, String>> events(WhoClient client) {
    WhoClient.Response response = client.get("/test/events");
    if (response.status() != 200) {
      throw new IllegalStateException("GET /test/events answered " + response.status());
    }
    List<Map<String, String>> events = new ArrayList<>();
    for (JsonNode node : response.json()) {
      Map<String, String> event = new LinkedHashMap<>();
      node.properties().forEach(e -> event.put(e.getKey(), e.getValue().asString()));
      events.add(event);
    }
    return events;
  }

  /**
   * Forgets the recorded events.
   *
   * @param client a client of the app
   */
  public static void clearEvents(WhoClient client) {
    client.delete("/test/events");
  }

  /**
   * Waits up to ten seconds for an event with all of the given entries.
   *
   * @param client a client of the app
   * @param expected entries the event must have, such as {@code type=SignInSucceeded}
   * @return the first matching event
   * @throws AssertionError if none arrives; the message lists the events seen
   */
  public static Map<String, String> awaitEvent(WhoClient client, Map<String, String> expected) {
    Instant deadline = Instant.now().plus(WAIT);
    List<Map<String, String>> seen;
    do {
      seen = events(client);
      for (Map<String, String> event : seen) {
        if (event.entrySet().containsAll(expected.entrySet())) {
          return event;
        }
      }
      pause();
    } while (Instant.now().isBefore(deadline));
    throw new AssertionError("No event with " + expected + " in " + seen);
  }

  /**
   * Waits up to ten seconds for an email to the address and returns its text.
   *
   * @param client a client of the app
   * @param address the recipient
   * @return the text of the last email sent to the address
   * @throws AssertionError if none arrives
   */
  public static String awaitMail(WhoClient client, String address) {
    Instant deadline = Instant.now().plus(WAIT);
    do {
      WhoClient.Response response =
          client.get("/test/mail?to=" + URLEncoder.encode(address, StandardCharsets.UTF_8));
      if (response.status() == 200) {
        return response.body();
      }
      pause();
    } while (Instant.now().isBefore(deadline));
    throw new AssertionError("No email to " + address);
  }

  private static void pause() {
    try {
      Thread.sleep(100);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
