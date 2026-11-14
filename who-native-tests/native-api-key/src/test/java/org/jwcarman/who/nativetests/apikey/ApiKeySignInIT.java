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
package org.jwcarman.who.nativetests.apikey;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.nativetests.support.AppLauncher;
import org.jwcarman.who.nativetests.support.RunningApp;
import org.jwcarman.who.nativetests.support.TestDatabase;
import org.jwcarman.who.nativetests.support.TestEndpoints;
import org.jwcarman.who.nativetests.support.WhoClient;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ApiKeySignInIT {

  private static final String HEADER = "X-API-Key";

  static TestDatabase database;
  static RunningApp app;

  @BeforeAll
  static void start() {
    database = TestDatabase.start();
    app =
        AppLauncher.start(
            NativeApiKeyApplication.class,
            AppLauncher.freePort(),
            List.of(
                "--spring.datasource.url=" + database.jdbcUrl(),
                "--spring.datasource.username=" + database.username(),
                "--spring.datasource.password=" + database.password()));
  }

  @AfterAll
  static void stop() {
    if (app != null) {
      app.close();
    }
    if (database != null) {
      database.close();
    }
  }

  static WhoClient client() {
    return WhoClient.stateless(app.baseUri());
  }

  /** A new identity with a new key: {@code identityId} and {@code key}. */
  static Map<String, String> newKey(WhoClient client, boolean admin) {
    WhoClient.Response response =
        client.post("/test/setup/api-key", WhoClient.json(Map.of("admin", admin)));
    assertThat(response.status()).isEqualTo(200);
    return Map.of(
        "identityId", response.json().path("identityId").asString(),
        "key", response.json().path("key").asString());
  }

  @Nested
  class Keys {

    @Test
    void a_valid_key_reaches_the_api() {
      try (WhoClient client = client()) {
        Map<String, String> key = newKey(client, false);

        WhoClient.Response response = client.get("/api/hello", HEADER, key.get("key"));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains(key.get("identityId"));
        // a valid key publishes no who event; Spring's AuthenticationSuccessEvent covers it
        assertThat(TestEndpoints.events(client))
            .noneMatch(event -> "SignInSucceeded".equals(event.get("type")));
      }
    }

    @Test
    void an_unknown_key_is_401_even_where_open() {
      try (WhoClient client = client()) {
        WhoClient.Response response = client.get("/api/open", HEADER, "who_" + "x".repeat(43));

        assertThat(response.status()).isEqualTo(401);
        TestEndpoints.awaitEvent(
            client, Map.of("type", "SignInFailed", "method", "API_KEY", "reason", "UNKNOWN_KEY"));
      }
    }

    @Test
    void no_key_reaches_the_open_endpoint_anonymously() {
      try (WhoClient client = client()) {
        WhoClient.Response response = client.get("/api/open");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains("anonymous");
      }
    }

    @Test
    void no_session_cookie_is_set() {
      try (WhoClient client = client()) {
        Map<String, String> key = newKey(client, false);

        WhoClient.Response response = client.get("/api/hello", HEADER, key.get("key"));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.headers().getOrDefault("set-cookie", List.of()))
            .noneMatch(c -> c.startsWith("JSESSIONID"));
      }
    }
  }

  @Nested
  class Permissions {

    @Test
    void admin_needs_the_permission() {
      try (WhoClient client = client()) {
        String adminKey = newKey(client, true).get("key");
        String userKey = newKey(client, false).get("key");

        assertThat(client.get("/api/admin", HEADER, adminKey).status()).isEqualTo(200);
        assertThat(client.get("/api/admin", HEADER, userKey).status()).isEqualTo(403);
      }
    }
  }
}
