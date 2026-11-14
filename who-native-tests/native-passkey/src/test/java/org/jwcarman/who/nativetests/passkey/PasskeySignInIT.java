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
package org.jwcarman.who.nativetests.passkey;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
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
import org.jwcarman.who.nativetests.support.PasskeyDevice;
import org.jwcarman.who.nativetests.support.RunningApp;
import org.jwcarman.who.nativetests.support.TestDatabase;
import org.jwcarman.who.nativetests.support.TestEndpoints;
import org.jwcarman.who.nativetests.support.WhoClient;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class PasskeySignInIT {

  private static final Pattern TOKEN = Pattern.compile("#token=([A-Za-z0-9_-]+)");
  private static final JsonMapper JSON = new JsonMapper();

  static TestDatabase database;
  static RunningApp app;
  static String origin;

  @BeforeAll
  static void start() {
    database = TestDatabase.start();
    int port = AppLauncher.freePort();
    origin = "http://localhost:" + port;
    app =
        AppLauncher.start(
            NativePasskeyApplication.class,
            port,
            List.of(
                "--spring.datasource.url=" + database.jdbcUrl(),
                "--spring.datasource.username=" + database.username(),
                "--spring.datasource.password=" + database.password(),
                "--who.email-link.link-base-url=" + origin + "/signin",
                "--who.passkey.allowed-origins=" + origin));
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

  /** Creates an identity, signs this client in with an email link, and returns the identity id. */
  static String signInByEmail(WhoClient client, boolean admin) {
    String address = "user-" + UUID.randomUUID() + "@example.test";
    WhoClient.Response setup =
        client.post(
            "/test/setup/identity", WhoClient.json(Map.of("email", address, "admin", admin)));
    assertThat(setup.status()).isEqualTo(200);
    assertThat(client.post("/ott/generate", WhoClient.json(Map.of("email", address))).status())
        .isEqualTo(202);
    Matcher token = TOKEN.matcher(TestEndpoints.awaitMail(client, address));
    assertThat(token.find()).isTrue();
    assertThat(client.post("/login/ott", WhoClient.json(Map.of("token", token.group(1)))).status())
        .isEqualTo(200);
    return setup.json().path("identityId").asString();
  }

  /** Registers a passkey on the device for the signed-in caller. */
  static void registerPasskey(WhoClient client, PasskeyDevice device) {
    WhoClient.Response options = client.post("/webauthn/register/options", null);
    assertThat(options.status()).isEqualTo(200);
    WhoClient.Response registered =
        client.post("/webauthn/register", device.register(options.body(), "test device"));
    assertThat(registered.status()).as(registered.body()).isEqualTo(200);
  }

  /** The body for {@code POST /login/webauthn}, as the device would answer the next challenge. */
  static String assertion(WhoClient client, PasskeyDevice device) {
    WhoClient.Response options = client.post("/webauthn/authenticate/options", null);
    assertThat(options.status()).isEqualTo(200);
    return device.signIn(options.body());
  }

  /** Signs in by email, registers a passkey, signs out: a caller who now has only the passkey. */
  static String withPasskeyOnly(WhoClient client, PasskeyDevice device, boolean admin) {
    String identityId = signInByEmail(client, admin);
    registerPasskey(client, device);
    assertThat(client.post("/logout", null).status()).isEqualTo(204);
    assertThat(client.get("/who/me").status()).isEqualTo(401);
    return identityId;
  }

  @Nested
  class SigningIn {

    @Test
    void registering_and_signing_in_with_a_passkey() {
      try (WhoClient client = client()) {
        PasskeyDevice device = new PasskeyDevice(origin);
        String identityId = withPasskeyOnly(client, device, false);

        WhoClient.Response signedIn = client.post("/login/webauthn", assertion(client, device));

        assertThat(signedIn.status()).as(signedIn.body()).isEqualTo(200);
        WhoClient.Response me = client.get("/who/me");
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.json().path("identityId").asString()).isEqualTo(identityId);
        TestEndpoints.awaitEvent(
            client, Map.of("type", "CredentialAdded", "kind", "PASSKEY", "identityId", identityId));
        TestEndpoints.awaitEvent(
            client,
            Map.of("type", "SignInSucceeded", "method", "PASSKEY", "identityId", identityId));
      }
    }

    @Test
    void a_forged_assertion_is_401() {
      try (WhoClient client = client()) {
        PasskeyDevice device = new PasskeyDevice(origin);
        withPasskeyOnly(client, device, false);
        ObjectNode forged = (ObjectNode) JSON.readTree(assertion(client, device));
        byte[] signature = new byte[64];
        ((ObjectNode) forged.path("response"))
            .put("signature", Base64.getUrlEncoder().withoutPadding().encodeToString(signature));

        WhoClient.Response response = client.post("/login/webauthn", forged.toString());

        assertThat(response.status()).isEqualTo(401);
        assertThat(client.get("/who/me").status()).isEqualTo(401);
        TestEndpoints.awaitEvent(
            client,
            Map.of("type", "SignInFailed", "method", "PASSKEY", "reason", "INVALID_ASSERTION"));
      }
    }

    @Test
    void registering_needs_a_signed_in_caller() {
      try (WhoClient client = client()) {
        assertThat(client.post("/webauthn/register/options", null).status()).isEqualTo(401);
      }
    }
  }

  @Nested
  class Permissions {

    @Test
    void admin_needs_the_permission() {
      try (WhoClient admin = client();
          WhoClient user = client()) {
        PasskeyDevice adminDevice = new PasskeyDevice(origin);
        withPasskeyOnly(admin, adminDevice, true);
        assertThat(admin.post("/login/webauthn", assertion(admin, adminDevice)).status())
            .isEqualTo(200);
        PasskeyDevice userDevice = new PasskeyDevice(origin);
        withPasskeyOnly(user, userDevice, false);
        assertThat(user.post("/login/webauthn", assertion(user, userDevice)).status())
            .isEqualTo(200);

        assertThat(admin.get("/api/admin").status()).isEqualTo(200);
        assertThat(user.get("/api/admin").status()).isEqualTo(403);
        assertThat(user.get("/api/hello").status()).isEqualTo(200);
      }
    }
  }
}
