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
package org.jwcarman.who.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.crypto.MessageDigests;
import org.jwcarman.who.core.crypto.Secrets;
import org.jwcarman.who.spring.security.WhoAuthenticationToken;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Runs in a real servlet container: the API key filter must not be registered there, so a key sent
 * to a chain that does not add it must not authenticate anyone.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = ApiKeyServletContainerTest.App.class)
class ApiKeyServletContainerTest {

  @Configuration
  @EnableAutoConfiguration(
      exclude = {SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
  @EnableWebSecurity
  static class App {

    @Bean
    DataSource dataSource() {
      return new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).build();
    }

    @Bean
    JdbcClient jdbcClient(DataSource dataSource) {
      return JdbcClient.create(dataSource);
    }

    @Bean
    SecurityFilterChain plain(HttpSecurity http) throws Exception {
      return http.authorizeHttpRequests(a -> a.anyRequest().permitAll())
          .csrf(c -> c.disable())
          .build();
    }

    @Bean
    WhoamiController whoamiController() {
      return new WhoamiController();
    }
  }

  @RestController
  static class WhoamiController {
    @GetMapping("/whoami")
    String whoami() {
      return String.valueOf(
          SecurityContextHolder.getContext().getAuthentication() instanceof WhoAuthenticationToken);
    }
  }

  @LocalServerPort private int port;
  @Autowired private JdbcClient jdbcClient;

  @Test
  void keyDoesNotAuthenticateOnAChainWithoutTheApiKeyFilter() throws Exception {
    // Plain INSERTs: the repositories' upserts are PostgreSQL-specific and this test runs on H2.
    UUID identityId = UUID.randomUUID();
    UUID credentialId = UUID.randomUUID();
    String key = "who_" + Secrets.token();
    jdbcClient
        .sql(
            "INSERT INTO who_identity (id, status, created_at, updated_at) VALUES (?, 'ACTIVE', ?, ?)")
        .params(identityId, LocalDateTime.now(), LocalDateTime.now())
        .update();
    jdbcClient
        .sql("INSERT INTO who_api_key_credential (id, name, key_hash) VALUES (?, 'servlet', ?)")
        .params(credentialId, MessageDigests.sha256Hex(key))
        .update();
    jdbcClient
        .sql("INSERT INTO who_credential_identity (credential_id, identity_id) VALUES (?, ?)")
        .params(credentialId, identityId)
        .update();

    HttpResponse<String> response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/whoami"))
                    .header("X-API-Key", key)
                    .build(),
                HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).isEqualTo("false");
  }
}
