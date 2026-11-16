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
package org.jwcarman.who.examples.api;

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

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Starts the API on a Testcontainers Postgres, with Docker Compose off. Tokens are signed here by a
 * key made for the run, whose public half Spring verifies them with, so no Keycloak is needed.
 * Spring uses a public key only when no {@code issuer-uri} is set, so the test clears it and checks
 * the issuer with a validator instead, as {@code issuer-uri} does; the audience check is the app's
 * own.
 */
@SpringBootTest(
    properties = {
      "spring.docker.compose.enabled=false",
      "spring.security.oauth2.resourceserver.jwt.issuer-uri=",
      "example.issuer=" + AbstractApiTest.ISSUER
    })
@Import(AbstractApiTest.IssuerCheck.class)
@AutoConfigureMockMvc
@Testcontainers
abstract class AbstractApiTest {

  static final String ISSUER = "http://localhost:8280/realms/who-tasks";
  static final String AUDIENCE = "who-tasks-api";
  static final String ALICE = "a11ce000-0000-4000-8000-000000000001";
  static final String BOB = "b0b00000-0000-4000-8000-000000000002";
  static final String CAROL = "ca201000-0000-4000-8000-000000000003";

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

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
      Path file = Files.createTempFile("who-example-api", ".pem");
      file.toFile().deleteOnExit();
      return Files.writeString(file, pem);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
