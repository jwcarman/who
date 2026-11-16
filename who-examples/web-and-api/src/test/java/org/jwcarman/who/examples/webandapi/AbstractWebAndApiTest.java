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
package org.jwcarman.who.examples.webandapi;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.emaillink.EmailCredential;
import org.jwcarman.who.emaillink.EmailCredentialService;
import org.jwcarman.who.rbac.RbacService;
import org.jwcarman.who.test.WhoTestSessions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Starts the app on a Testcontainers Postgres, with Docker Compose off. Tokens are signed here by a
 * key made for the run, whose public half Spring verifies them with, so no Keycloak is needed.
 * Spring uses a public key only when no {@code issuer-uri} is set, so the test clears it and checks
 * the issuer with a validator instead, as {@code issuer-uri} does; the audience check is the app's
 * own.
 */
@SpringBootTest(
    properties = {
      "spring.docker.compose.enabled=false",
      "spring.security.oauth2.resourceserver.jwt.issuer-uri=",
      "example.issuer=" + AbstractWebAndApiTest.ISSUER
    })
@Import(AbstractWebAndApiTest.IssuerCheck.class)
@AutoConfigureMockMvc
abstract class AbstractWebAndApiTest {

  static final String ISSUER = "http://localhost:8280/realms/who-tasks";
  static final String AUDIENCE = "who-tasks-api";
  static final String ALICE = "a11ce000-0000-4000-8000-000000000001";
  static final String BOB = "b0b00000-0000-4000-8000-000000000002";
  static final String CAROL = "ca201000-0000-4000-8000-000000000003";

  /**
   * One database for every test class: started once, not per class, because the classes share one
   * cached Spring context, which must not outlive its database. Testcontainers removes it after the
   * run.
   */
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

  static {
    POSTGRES.start();
  }

  static final KeyPair SIGNING_KEY = rsa();
  static final Path PUBLIC_KEY = publicKeyPem(SIGNING_KEY);

  @Autowired MockMvc mockMvc;

  /** The issuer check {@code issuer-uri} would add, which Spring adds to its own validators. */
  @TestConfiguration
  static class IssuerCheck {

    @Bean
    OAuth2TokenValidator<Jwt> issuerValidator() {
      return new JwtIssuerValidator(ISSUER);
    }
  }

  @DynamicPropertySource
  static void verifyWithTheTestKey(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.security.oauth2.resourceserver.jwt.public-key-location",
        () -> PUBLIC_KEY.toUri().toString());
  }

  /** A valid access token for {@code subject}, issued for this API. */
  static String token(String subject) {
    return token(SIGNING_KEY, ISSUER, subject, AUDIENCE, Instant.now().plus(Duration.ofMinutes(5)));
  }

  static String token(
      KeyPair key, String issuer, String subject, String audience, Instant expiresAt) {
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(issuer)
            .subject(subject)
            .audience(List.of(audience))
            .issueTime(Date.from(expiresAt.minus(Duration.ofMinutes(10))))
            .expirationTime(Date.from(expiresAt))
            .build();
    SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
    try {
      jwt.sign(new RSASSASigner((RSAPrivateKey) key.getPrivate()));
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
    return jwt.serialize();
  }

  @Autowired WhoService whoService;
  @Autowired EmailCredentialService emailCredentials;
  @Autowired RbacService rbacService;

  /**
   * A browser session for a new person with {@code role}, as the website's sign-in leaves one.
   *
   * @param role the person's role on the board
   * @return the session
   */
  MockHttpSession signedInAs(TeamRole role) {
    return person(role).session();
  }

  /**
   * A person signed in to the website.
   *
   * @param identity who they are
   * @param session their browser session
   */
  record Person(Identity identity, MockHttpSession session) {}

  /**
   * A new person with {@code role}, signed in to the website.
   *
   * @param role the person's role on the board
   * @return the person and their session
   */
  Person person(TeamRole role) {
    return person(role, Instant.now());
  }

  /**
   * A new person with {@code role}, who signed in to the website at {@code signedInAt}.
   *
   * @param role the person's role on the board
   * @param signedInAt when they signed in
   * @return the person and their session
   */
  Person person(TeamRole role, Instant signedInAt) {
    Identity identity = whoService.createIdentity();
    EmailCredential credential =
        emailCredentials.register(identity, UUID.randomUUID() + "@example.com");
    rbacService.assignRoleByName(identity, role);
    return new Person(
        identity, WhoTestSessions.signedIn(identity.id(), credential.id(), signedInAt));
  }

  static String bearer(String token) {
    return "Bearer " + token;
  }

  static KeyPair rsa() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Path publicKeyPem(KeyPair key) {
    String pem =
        "-----BEGIN PUBLIC KEY-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes())
                .encodeToString(key.getPublic().getEncoded())
            + "\n-----END PUBLIC KEY-----\n";
    try {
      Path file = Files.createTempFile("who-example-web-and-api", ".pem");
      file.toFile().deleteOnExit();
      return Files.writeString(file, pem);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
