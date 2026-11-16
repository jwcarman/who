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
package org.jwcarman.who.examples.observability;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Reads sign-in codes back out of Mailpit, through its API, so the traffic generator can complete
 * an email sign-in as a person would. An example shortcut: nothing like this belongs in production.
 */
class Mailpit {

  private static final Pattern CODE = Pattern.compile("enter this code: (\\S+)");

  /**
   * A page of Mailpit's search results.
   *
   * @param messages the newest first
   */
  record Search(List<Summary> messages) {}

  /**
   * A message in the results.
   *
   * @param id Mailpit's id for it
   */
  record Summary(@JsonProperty("ID") String id) {}

  /**
   * A message's content.
   *
   * @param text its plain text
   */
  record Message(@JsonProperty("Text") String text) {}

  private final RestClient client;

  Mailpit(RestClient client) {
    this.client = client;
  }

  /**
   * The code in the newest sign-in email to {@code address}.
   *
   * @param address the address the email went to
   * @return the code, or empty when no email has arrived
   */
  Optional<String> latestCode(String address) {
    Search search =
        client
            .get()
            .uri(
                b ->
                    b.path("/api/v1/search")
                        .queryParam("query", "to:" + address)
                        .queryParam("limit", 1)
                        .build())
            .retrieve()
            .body(Search.class);
    if (search == null || search.messages() == null || search.messages().isEmpty()) {
      return Optional.empty();
    }
    Message message =
        client
            .get()
            .uri("/api/v1/message/{id}", search.messages().getFirst().id())
            .retrieve()
            .body(Message.class);
    if (message == null || message.text() == null) {
      return Optional.empty();
    }
    Matcher matcher = CODE.matcher(message.text());
    return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
  }
}
