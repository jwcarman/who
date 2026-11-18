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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.IdentityStatus;
import org.jwcarman.who.core.email.WhoEmailSender;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.emaillink.EmailCredentialService;
import org.jwcarman.who.test.WhoMockMvcBrowser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * An application that sets Jackson its own way (snake_case names, dates as numbers, nulls left out)
 * still gets every who answer in who's shape, which who's TypeScript client reads.
 */
@SpringBootTest(
    classes = AnswersIgnoreApplicationJacksonTest.App.class,
    properties = {
      "who.initialize-schema=always",
      "who.email-link.link-base-url=https://app.example.com/signin",
      "who.email-link.code-secret=test-code-secret-0123456789-abcdefghij",
      "who.passkey.enabled=false",
      "who.jwt.enabled=false",
      "who.turnstile.enabled=false",
      "spring.session.jdbc.initialize-schema=always",
      "spring.jackson.property-naming-strategy=SNAKE_CASE",
      "spring.jackson.datatype.datetime.write-dates-as-timestamps=true",
      "spring.jackson.default-property-inclusion=non_null"
    })
@AutoConfigureMockMvc
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class AnswersIgnoreApplicationJacksonTest {

  private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");
  private static final JsonMapper READER = JsonMapper.builder().build();

  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  static class App {

    @Bean
    Clock clock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    @Bean
    WhoEmailSender whoEmailSender() {
      return _ -> {};
    }
  }

  @Autowired private MockMvc mvc;
  @Autowired private IdentityRepository identities;
  @Autowired private EmailCredentialService emailCredentials;

  private Identity identity;
  private WhoMockMvcBrowser browser;

  @BeforeEach
  void signIn() {
    identity = identities.save(Identity.create(IdentityStatus.ACTIVE));
    UUID credentialId =
        emailCredentials.register(identity, "jackson-" + identity.id() + "@example.com").id();
    browser = new WhoMockMvcBrowser(mvc).signedInAs(identity.id(), credentialId, NOW);
  }

  private static JsonNode body(MvcResult result) throws Exception {
    return READER.readTree(result.getResponse().getContentAsString());
  }

  @Nested
  class Me {

    @Test
    void the_answer_keeps_whos_names_and_formats() throws Exception {
      MvcResult result = browser.perform(get("/who/me")).andReturn();

      assertThat(result.getResponse().getStatus()).isEqualTo(200);
      JsonNode me = body(result);
      assertThat(me.propertyNames())
          .containsExactlyInAnyOrder(
              "identityId", "permissions", "signInMethod", "recentSignInUntil", "credentials");
      assertThat(me.get("identityId").asString()).isEqualTo(identity.id().toString());
      assertThat(me.get("recentSignInUntil").isString()).isTrue();
    }

    @Test
    void a_signed_out_request_is_not_signed_in() throws Exception {
      MvcResult result = mvc.perform(get("/who/me")).andReturn();

      // who's entry point answers it, outside Spring MVC, through the application's advice
      assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }
  }
}
