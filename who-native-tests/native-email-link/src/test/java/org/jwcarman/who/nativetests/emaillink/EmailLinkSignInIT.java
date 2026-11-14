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
class EmailLinkSignInIT {

  private static final Pattern TOKEN = Pattern.compile("#token=([A-Za-z0-9_-]+)");
  private static final Pattern CODE = Pattern.compile("Or enter this code: (\\S+)");

  static TestDatabase database;
  static RunningApp app;

  @BeforeAll
  static void start() {
    database = TestDatabase.start();
    int port = AppLauncher.freePort();
    app =
        AppLauncher.start(
            NativeEmailLinkApplication.class,
            port,
            List.of(
                "--spring.datasource.url=" + database.jdbcUrl(),
                "--spring.datasource.username=" + database.username(),
                "--spring.datasource.password=" + database.password(),
                "--who.email-link.link-base-url=http://localhost:" + port + "/signin"));
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
    return new WhoClient(app.baseUri());
  }

  static String newAddress() {
    return "user-" + UUID.randomUUID() + "@example.test";
  }

  /** Creates an identity with that email address, holding native.admin when admin is true. */
  static String setUpIdentity(WhoClient client, String address, boolean admin) {
    WhoClient.Response response =
        client.post(
            "/test/setup/identity", WhoClient.json(Map.of("email", address, "admin", admin)));
    assertThat(response.status()).isEqualTo(200);
    return response.json().path("identityId").asString();
  }

  /** Asks for a sign-in email and returns its text. */
  static String requestEmail(WhoClient client, String address) {
    assertThat(client.post("/ott/generate", WhoClient.json(Map.of("email", address))).status())
        .isEqualTo(202);
    return TestEndpoints.awaitMail(client, address);
  }

  static String find(Pattern pattern, String text) {
    Matcher matcher = pattern.matcher(text);
    assertThat(matcher.find()).as("%s in %s", pattern, text).isTrue();
    return matcher.group(1);
  }

  static String signInByLink(WhoClient client, String address) {
    String token = find(TOKEN, requestEmail(client, address));
    WhoClient.Response response = client.post("/login/ott", WhoClient.json(Map.of("token", token)));
    assertThat(response.status()).isEqualTo(200);
    return response.json().path("identityId").asString();
  }

  @Nested
  class Links {

    @Test
    void a_link_signs_in() {
      try (WhoClient client = client()) {
        String address = newAddress();
        String identityId = setUpIdentity(client, address, false);

        assertThat(signInByLink(client, address)).isEqualTo(identityId);

        WhoClient.Response me = client.get("/who/me");
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.json().path("identityId").asString()).isEqualTo(identityId);
        assertThat(client.get("/api/hello").body()).contains(identityId);
        TestEndpoints.awaitEvent(
            client,
            Map.of("type", "SignInEmailRequested", "outcome", "SENT", "identityId", identityId));
        TestEndpoints.awaitEvent(
            client,
            Map.of("type", "SignInSucceeded", "method", "EMAIL_LINK", "identityId", identityId));
      }
    }
  }

  @Nested
  class Codes {

    @Test
    void a_code_signs_in() {
      try (WhoClient client = client()) {
        String address = newAddress();
        String identityId = setUpIdentity(client, address, false);
        String code = find(CODE, requestEmail(client, address));

        WhoClient.Response response =
            client.post("/login/ott", WhoClient.json(Map.of("email", address, "code", code)));

        assertThat(response.status()).isEqualTo(200);
        assertThat(client.get("/who/me").json().path("identityId").asString())
            .isEqualTo(identityId);
        TestEndpoints.awaitEvent(
            client,
            Map.of("type", "SignInSucceeded", "method", "EMAIL_CODE", "identityId", identityId));
      }
    }

    @Test
    void a_wrong_code_is_401() {
      try (WhoClient client = client()) {
        String address = newAddress();
        String identityId = setUpIdentity(client, address, false);
        String code = find(CODE, requestEmail(client, address));
        String wrong = code.startsWith("0") ? "111-111" : "000-000";

        WhoClient.Response response =
            client.post("/login/ott", WhoClient.json(Map.of("email", address, "code", wrong)));

        assertThat(response.status()).isEqualTo(401);
        assertThat(client.get("/who/me").status()).isEqualTo(401);
        // a failed sign-in may carry no identity (events guide); only this test sends a wrong code
        TestEndpoints.awaitEvent(
            client, Map.of("type", "SignInFailed", "method", "EMAIL_CODE", "reason", "WRONG_CODE"));
        assertThat(identityId).isNotBlank();
      }
    }
  }

  @Nested
  class Permissions {

    @Test
    void admin_needs_the_permission() {
      try (WhoClient admin = client();
          WhoClient user = client()) {
        String adminAddress = newAddress();
        setUpIdentity(admin, adminAddress, true);
        signInByLink(admin, adminAddress);
        String userAddress = newAddress();
        setUpIdentity(user, userAddress, false);
        signInByLink(user, userAddress);

        assertThat(admin.get("/api/admin").status()).isEqualTo(200);
        assertThat(user.get("/api/admin").status()).isEqualTo(403);
        assertThat(user.get("/api/hello").status()).isEqualTo(200);
      }
    }

    @Test
    void a_signed_out_caller_is_401() {
      try (WhoClient client = client()) {
        assertThat(client.get("/api/hello").status()).isEqualTo(401);
        assertThat(client.get("/who/me").status()).isEqualTo(401);
      }
    }
  }

  @Nested
  class SigningOut {

    @Test
    void signing_out_ends_the_session() {
      try (WhoClient client = client()) {
        String address = newAddress();
        String identityId = setUpIdentity(client, address, false);
        signInByLink(client, address);

        assertThat(client.post("/logout", null).status()).isEqualTo(204);

        assertThat(client.get("/who/me").status()).isEqualTo(401);
        TestEndpoints.awaitEvent(client, Map.of("type", "SignedOut", "identityId", identityId));
        // the cookie was cleared at sign-out; the next POST still carries a fresh token
        assertThat(client.post("/ott/generate", WhoClient.json(Map.of("email", address))).status())
            .isEqualTo(202);
      }
    }
  }

  @Nested
  class Events {

    @Test
    void an_event_the_app_does_not_map_is_still_recorded() {
      try (WhoClient client = client()) {
        String identityId = setUpIdentity(client, newAddress(), false);

        assertThat(TestEndpoints.events(client))
            .anySatisfy(e -> assertThat(e).containsEntry("type", "IdentityCreated"));
        assertThat(identityId).isNotBlank();
      }
    }

    @Test
    void the_record_can_be_cleared() {
      try (WhoClient client = client()) {
        String identityId = setUpIdentity(client, newAddress(), false);

        TestEndpoints.clearEvents(client);

        // only this test's events are checked: another test's email may still be on its way
        assertThat(TestEndpoints.events(client))
            .noneSatisfy(e -> assertThat(e).containsEntry("identityId", identityId));
      }
    }
  }
}
