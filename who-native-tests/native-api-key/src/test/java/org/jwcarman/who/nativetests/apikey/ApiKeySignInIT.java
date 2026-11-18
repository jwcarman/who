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
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
  private static final Pattern TOKEN = Pattern.compile("#token=([A-Za-z0-9_-]+)");

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

  /**
   * A new identity with a new key: {@code identityId} and {@code key}. Set up from a browser
   * client, which sends the CSRF token the app's browser chain asks of a POST.
   */
  static Map<String, String> newKey(WhoClient client, boolean admin) {
    try (WhoClient setup = browser()) {
      WhoClient.Response response =
          setup.post("/test/setup/api-key", WhoClient.json(Map.of("admin", admin)));
      assertThat(response.status()).isEqualTo(200);
      return Map.of(
          "identityId", response.json().path("identityId").asString(),
          "key", response.json().path("key").asString());
    }
  }

  /** A browser: keeps the session cookie and sends the CSRF token. */
  static WhoClient browser() {
    return new WhoClient(app.baseUri());
  }

  /** Creates an identity that holds the user permission and signs the browser in by email link. */
  static void signInByEmail(WhoClient browser) {
    String address = "user-" + UUID.randomUUID() + "@example.test";
    assertThat(
            browser.post("/test/setup/identity", WhoClient.json(Map.of("email", address))).status())
        .isEqualTo(200);
    assertThat(browser.post("/who/email/send", WhoClient.json(Map.of("email", address))).status())
        .isEqualTo(202);
    Matcher token = TOKEN.matcher(TestEndpoints.awaitMail(browser, address));
    assertThat(token.find()).isTrue();
    assertThat(
            browser
                .post("/who/email/sign-in", WhoClient.json(Map.of("token", token.group(1))))
                .status())
        .isEqualTo(200);
  }

  @Nested
  class Managing {

    @Test
    void a_browser_creates_lists_rotates_and_revokes_a_key() {
      try (WhoClient browser = browser();
          WhoClient caller = client()) {
        signInByEmail(browser);

        WhoClient.Response created =
            browser.post(
                "/who/me/api-keys",
                WhoClient.json(
                    Map.of(
                        "name", "ci",
                        "lifetime", "P1D",
                        "scopes", List.of("native.user"))));
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        assertThat(created.json().propertyNames())
            .containsExactlyInAnyOrder("id", "name", "scopes", "expiresAt", "key");
        String id = created.json().path("id").asString();
        String key = created.json().path("key").asString();

        WhoClient.Response listed = browser.get("/who/me/api-keys");
        assertThat(listed.status()).as(listed.body()).isEqualTo(200);
        assertThat(listed.json().get(0).path("name").asString()).isEqualTo("ci");
        assertThat(listed.json().get(0).path("lastUsedAt").isNull()).isTrue();
        assertThat(listed.body()).doesNotContain(key);

        WhoClient.Response current = caller.get("/who/me/api-keys/current", HEADER, key);
        assertThat(current.status()).as(current.body()).isEqualTo(200);
        assertThat(current.json().path("id").asString()).isEqualTo(id);

        WhoClient.Response rotated =
            browser.post(
                "/who/me/api-keys/" + id + "/rotations", WhoClient.json(Map.of("overlap", "PT0S")));
        assertThat(rotated.status()).as(rotated.body()).isEqualTo(201);
        String newId = rotated.json().path("id").asString();
        String newKey = rotated.json().path("key").asString();
        assertThat(newId).isNotEqualTo(id);
        assertThat(caller.get("/api/hello", HEADER, newKey).status()).isEqualTo(200);

        assertThat(browser.delete("/who/me/api-keys/" + newId).status()).isEqualTo(204);
        assertThat(caller.get("/api/hello", HEADER, newKey).status()).isEqualTo(401);
      }
    }

    @Test
    void a_key_cannot_manage_keys() {
      try (WhoClient caller = client()) {
        Map<String, String> key = newKey(caller, false);

        WhoClient.Response response = caller.get("/who/me/api-keys", HEADER, key.get("key"));

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.json().path("code").asString()).isEqualTo("api-key-cannot-manage-keys");
      }
    }
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
            .noneMatch(
                event ->
                    "SignInSucceeded".equals(event.get("type"))
                        && key.get("identityId").equals(event.get("identityId")));
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
