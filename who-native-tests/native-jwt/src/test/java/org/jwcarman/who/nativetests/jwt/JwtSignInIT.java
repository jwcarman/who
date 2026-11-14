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
package org.jwcarman.who.nativetests.jwt;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class JwtSignInIT {

  private static final String ISSUER = "https://issuer.example.test";

  static TestDatabase database;
  static RunningApp app;
  static KeyPair keys;

  @BeforeAll
  static void start() throws NoSuchAlgorithmException, IOException {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    keys = generator.generateKeyPair();
    Path publicKey = Path.of("target", "jwt-public-key.pem").toAbsolutePath();
    Files.writeString(
        publicKey,
        "-----BEGIN PUBLIC KEY-----\n"
            + Base64.getMimeEncoder().encodeToString(keys.getPublic().getEncoded())
            + "\n-----END PUBLIC KEY-----\n");
    database = TestDatabase.start();
    app =
        AppLauncher.start(
            NativeJwtApplication.class,
            AppLauncher.freePort(),
            List.of(
                "--spring.datasource.url=" + database.jdbcUrl(),
                "--spring.datasource.username=" + database.username(),
                "--spring.datasource.password=" + database.password(),
                "--spring.security.oauth2.resourceserver.jwt.public-key-location=file:"
                    + publicKey));
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

  /** A signed RS256 token from {@link #ISSUER}, with that subject, or no {@code sub} if null. */
  static String token(String subject) {
    JWTClaimsSet.Builder claims =
        new JWTClaimsSet.Builder()
            .issuer(ISSUER)
            .expirationTime(Date.from(Instant.now().plusSeconds(300)));
    if (subject != null) {
      claims.subject(subject);
    }
    SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
    try {
      jwt.sign(new RSASSASigner((RSAPrivateKey) keys.getPrivate()));
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
    return jwt.serialize();
  }

  static String bearer(String subject) {
    return "Bearer " + token(subject);
  }

  /** Links a new subject to a new identity and returns the subject and the identity id. */
  static Map<String, String> linkedSubject(WhoClient client, boolean admin) {
    String subject = "subject-" + UUID.randomUUID();
    WhoClient.Response response =
        client.post(
            "/test/setup/jwt",
            WhoClient.json(Map.of("issuer", ISSUER, "subject", subject, "admin", admin)));
    assertThat(response.status()).isEqualTo(200);
    return Map.of("subject", subject, "identityId", response.json().path("identityId").asString());
  }

  @Nested
  class Tokens {

    @Test
    void a_known_subject_reaches_the_api() {
      try (WhoClient client = client()) {
        Map<String, String> linked = linkedSubject(client, false);

        WhoClient.Response response =
            client.get("/api/hello", "Authorization", bearer(linked.get("subject")));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains(linked.get("identityId"));
        TestEndpoints.awaitEvent(
            client,
            Map.of(
                "type",
                "SignInSucceeded",
                "method",
                "JWT",
                "identityId",
                linked.get("identityId")));
      }
    }

    @Test
    void an_unknown_subject_is_401() {
      try (WhoClient client = client()) {
        WhoClient.Response response =
            client.get("/api/hello", "Authorization", bearer("nobody-" + UUID.randomUUID()));

        assertThat(response.status()).isEqualTo(401);
        TestEndpoints.awaitEvent(
            client,
            Map.of("type", "SignInFailed", "method", "JWT", "reason", "UNKNOWN_CREDENTIAL"));
      }
    }

    @Test
    void a_token_without_sub_is_401() {
      try (WhoClient client = client()) {
        WhoClient.Response response = client.get("/api/hello", "Authorization", bearer(null));

        assertThat(response.status()).isEqualTo(401);
        TestEndpoints.awaitEvent(
            client, Map.of("type", "SignInFailed", "method", "JWT", "reason", "MISSING_CLAIMS"));
      }
    }

    @Test
    void no_token_is_401() {
      try (WhoClient client = client()) {
        assertThat(client.get("/api/hello").status()).isEqualTo(401);
      }
    }
  }

  @Nested
  class Permissions {

    @Test
    void admin_needs_the_permission() {
      try (WhoClient client = client()) {
        String admin = linkedSubject(client, true).get("subject");
        String user = linkedSubject(client, false).get("subject");

        assertThat(client.get("/api/admin", "Authorization", bearer(admin)).status())
            .isEqualTo(200);
        assertThat(client.get("/api/admin", "Authorization", bearer(user)).status()).isEqualTo(403);
      }
    }
  }
}
