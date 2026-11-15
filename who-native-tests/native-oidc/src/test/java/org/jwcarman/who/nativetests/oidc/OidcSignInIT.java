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
package org.jwcarman.who.nativetests.oidc;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.nativetests.support.AppLauncher;
import org.jwcarman.who.nativetests.support.RunningApp;
import org.jwcarman.who.nativetests.support.TestDatabase;
import org.jwcarman.who.nativetests.support.TestEndpoints;
import org.jwcarman.who.nativetests.support.WhoClient;
import org.jwcarman.who.test.StubOidcProvider;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class OidcSignInIT {

  private static final HttpClient AT_PROVIDER =
      HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

  static StubOidcProvider provider;
  static TestDatabase database;
  static RunningApp app;

  @BeforeAll
  static void start() {
    provider = StubOidcProvider.start();
    database = TestDatabase.start();
    app =
        AppLauncher.start(
            NativeOidcApplication.class,
            AppLauncher.freePort(),
            List.of(
                "--spring.datasource.url=" + database.jdbcUrl(),
                "--spring.datasource.username=" + database.username(),
                "--spring.datasource.password=" + database.password(),
                "--spring.security.oauth2.client.provider.test.issuer-uri=" + provider.issuer()));
  }

  @AfterAll
  static void stop() {
    if (app != null) {
      app.close();
    }
    if (database != null) {
      database.close();
    }
    if (provider != null) {
      provider.close();
    }
  }

  static WhoClient client() {
    return new WhoClient(app.baseUri());
  }

  static String location(WhoClient.Response response) {
    assertThat(response.status()).as(response.body()).isEqualTo(302);
    return response.headers().get("location").getFirst();
  }

  /** Starts sign-in at the app, lets the provider redirect back, and returns where it lands. */
  static String signInAtProvider(WhoClient client, String subject)
      throws IOException, InterruptedException {
    provider.nextUser(subject, Map.of("email", subject + "@example.test"));
    return finishAtProvider(client, location(client.get("/oauth2/authorization/test")));
  }

  /** Starts a link at the app, lets the provider redirect back, and returns where it lands. */
  static String linkAtProvider(WhoClient client, String subject)
      throws IOException, InterruptedException {
    provider.nextUser(subject, Map.of("email", subject + "@example.test"));
    return finishAtProvider(client, location(client.post("/who/oidc/link/test", null)));
  }

  static String finishAtProvider(WhoClient client, String authorize)
      throws IOException, InterruptedException {
    assertThat(authorize).startsWith(provider.issuer() + "/authorize");
    HttpResponse<Void> atProvider =
        AT_PROVIDER.send(
            HttpRequest.newBuilder(URI.create(authorize)).build(),
            HttpResponse.BodyHandlers.discarding());
    String callback = atProvider.headers().firstValue("Location").orElseThrow();
    return location(client.get(callback));
  }

  static String linkedAccount() {
    String subject = "linked-" + UUID.randomUUID();
    try (WhoClient setup = client()) {
      assertThat(
              setup.post("/test/setup/linked", WhoClient.json(Map.of("subject", subject))).status())
          .isEqualTo(200);
    }
    return subject;
  }

  static void openSignUp(boolean open) {
    try (WhoClient setup = client()) {
      assertThat(setup.post("/test/policy", WhoClient.json(Map.of("open", open))).status())
          .isEqualTo(204);
    }
  }

  @Test
  void signs_in_a_linked_account() throws Exception {
    String subject = linkedAccount();
    try (WhoClient client = client()) {
      assertThat(signInAtProvider(client, subject)).endsWith("/");

      assertThat(client.get("/who/me").status()).isEqualTo(200);
      TestEndpoints.awaitEvent(client, Map.of("type", "SignInSucceeded", "method", "OIDC"));
    }
  }

  @Test
  void closed_policy_refuses_sign_up() throws Exception {
    openSignUp(false);
    try (WhoClient client = client()) {
      assertThat(signInAtProvider(client, "new-" + UUID.randomUUID()))
          .endsWith("/?error=no-account");
      WhoClient.Response pending = client.get("/who/oidc/pending");
      assertThat(pending.status()).isEqualTo(200);
      assertThat(pending.json().path("canSignUp").asBoolean()).isTrue();

      assertThat(client.post("/who/oidc/sign-up", null).status()).isEqualTo(403);
      assertThat(client.get("/who/me").status()).isEqualTo(401);
    }
  }

  @Test
  void allowing_policy_signs_up() throws Exception {
    openSignUp(true);
    try {
      try (WhoClient client = client()) {
        assertThat(signInAtProvider(client, "new-" + UUID.randomUUID()))
            .endsWith("/?error=no-account");

        WhoClient.Response signedUp = client.post("/who/oidc/sign-up", null);

        assertThat(signedUp.status()).as(signedUp.body()).isEqualTo(200);
        assertThat(client.get("/who/me").status()).isEqualTo(200);
      }
    } finally {
      openSignUp(false);
    }
  }

  @Test
  void links_while_signed_in() throws Exception {
    String linked = linkedAccount();
    String another = "another-" + UUID.randomUUID();
    try (WhoClient client = client()) {
      signInAtProvider(client, linked);
      assertThat(signInAtProvider(client, another)).endsWith("/?error=signed-in");
      assertThat(linkAtProvider(client, another)).endsWith("/?error=no-account");
      assertThat(client.get("/who/oidc/pending").json().path("canLink").asBoolean()).isTrue();

      WhoClient.Response link = client.post("/who/oidc/link", null);

      assertThat(link.status()).as(link.body()).isEqualTo(200);
      TestEndpoints.awaitEvent(client, Map.of("type", "CredentialAdded", "kind", "FEDERATED"));
    }
    try (WhoClient fresh = client()) {
      assertThat(signInAtProvider(fresh, another)).endsWith("/");
      assertThat(fresh.get("/who/me").status()).isEqualTo(200);
    }
  }
}
