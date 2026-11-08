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
import static org.springframework.security.config.Customizer.withDefaults;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

import javax.sql.DataSource;

import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.IdentityStatus;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.core.spi.PermissionsResolver;
import org.jwcarman.who.emaillink.EmailCredentialService;
import org.jwcarman.who.emaillink.EmailLinkSender;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.jwcarman.who.spring.security.WhoWebSecurity;
import org.jwcarman.who.test.WhoMockMvcBrowser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Sessions stored by Spring Session JDBC on PostgreSQL, after a real email-link sign-in: the
 * principal index finds the session, and the session holds nothing but who's own attributes.
 */
@SpringBootTest(
    classes = SpringSessionJdbcTest.App.class,
    properties = {
      "who.initialize-schema=always",
      "who.email-link.link-base-url=https://app.example.com/signin",
      "spring.session.jdbc.initialize-schema=never"
    })
@AutoConfigureMockMvc
@Testcontainers
class SpringSessionJdbcTest {

  private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");
  private static final String ADDRESS = "owner@example.com";
  private static final String XSRF_COOKIE = "XSRF-TOKEN";
  private static final String SESSION_COOKIE = "SESSION";

  // Singleton container — started once per JVM and shared across all test classes.
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
    RecordingSender recordingSender() {
      return new RecordingSender();
    }

    @Bean
    Executor whoEmailLinkExecutor() {
      return Runnable::run;
    }

    @Bean
    @Primary
    PermissionsResolver stubPermissionsResolver() {
      return id -> Set.of("task.read");
    }

    @Bean
    SecurityFilterChain browserChain(HttpSecurity http) {
      http.authorizeHttpRequests(a -> a.anyRequest().authenticated())
          .with(WhoWebSecurity.passwordless(), withDefaults());
      return http.build();
    }
  }

  static final class RecordingSender implements EmailLinkSender {

    final List<String> tokens = new CopyOnWriteArrayList<>();

    @Override
    public void send(String address, String link) {
      tokens.add(link.substring(link.indexOf("#token=") + "#token=".length()));
    }
  }

  /** A browser's cookie jar, since Spring Session identifies the session by its own cookie. */
  private static final class Browser {

    private final MockMvc mvc;
    private final Map<String, String> cookies = new HashMap<>();

    private Browser(MockMvc mvc) {
      this.mvc = mvc;
    }

    private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
      cookies.forEach((name, value) -> request.cookie(new Cookie(name, value)));
      if (cookies.containsKey(XSRF_COOKIE)) {
        request.header("X-XSRF-TOKEN", cookies.get(XSRF_COOKIE));
      }
      MvcResult result = mvc.perform(request).andReturn();
      for (Cookie set : result.getResponse().getCookies()) {
        if (set.getMaxAge() == 0 || set.getValue() == null || set.getValue().isEmpty()) {
          cookies.remove(set.getName());
        } else {
          cookies.put(set.getName(), set.getValue());
        }
      }
      return result;
    }
  }

  @Autowired private WebApplicationContext context;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcClient jdbcClient;
  @Autowired private RecordingSender sender;
  @Autowired private IdentityRepository identityRepository;
  @Autowired private EmailCredentialService emailCredentialService;

  @Autowired
  @Qualifier("springSessionRepositoryFilter")
  private Filter sessionFilter;

  @Autowired private FindByIndexNameSessionRepository<? extends Session> sessions;

  /** The MockMvc Boot builds, with every filter bean (Spring Session's included) in the chain. */
  @Autowired private MockMvc bootMvc;

  private MockMvc mvc;
  private Identity identity;
  private UUID credentialId;

  @BeforeEach
  void setUp() {
    jdbcClient.sql("DROP TABLE IF EXISTS spring_session_attributes, spring_session").update();
    new ResourceDatabasePopulator(
            new ClassPathResource("org/springframework/session/jdbc/schema-postgresql.sql"))
        .execute(dataSource);
    jdbcClient.sql("DELETE FROM who_email_link_token").update();
    jdbcClient.sql("DELETE FROM who_email_credential").update();
    jdbcClient.sql("DELETE FROM who_credential_identity").update();
    jdbcClient.sql("DELETE FROM who_identity").update();
    sender.tokens.clear();

    identity = identityRepository.save(Identity.create(IdentityStatus.ACTIVE));
    credentialId = emailCredentialService.register(identity, ADDRESS).id();
    mvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(sessionFilter)
            .apply(springSecurity())
            .build();
  }

  @Test
  void findByPrincipalNameFindsTheSession() throws Exception {
    Browser browser = new Browser(mvc);
    browser.perform(get("/who/me"));
    browser.perform(
        post("/ott/generate")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + ADDRESS + "\"}"));
    assertThat(sender.tokens).hasSize(1);

    MvcResult signedIn =
        browser.perform(
            post("/login/ott")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + sender.tokens.getFirst() + "\"}"));
    assertThat(signedIn.getResponse().getStatus()).isEqualTo(200);
    assertThat(browser.perform(get("/who/me")).getResponse().getStatus()).isEqualTo(200);

    Map<String, ? extends Session> found = sessions.findByPrincipalName(identity.id().toString());

    assertThat(found).hasSize(1);
    Session session = found.values().iterator().next();
    assertThat(session.getAttributeNames())
        .containsExactlyInAnyOrder(
            WhoSessionSecurityContextRepository.SESSION_ATTRIBUTE,
            FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME);
    assertThat(browser.cookies).containsKey(SESSION_COOKIE);
  }

  @Test
  void browserSignsInUnderSpringSessionJdbc() throws Exception {
    WhoMockMvcBrowser browser =
        new WhoMockMvcBrowser(bootMvc).signedInAs(identity.id(), credentialId, NOW);

    browser
        .perform(get("/who/me"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.identityId").value(identity.id().toString()));
    assertThat(sessions.findByPrincipalName(identity.id().toString())).hasSize(1);

    browser.perform(post("/logout")).andExpect(status().isNoContent());

    browser.perform(get("/who/me")).andExpect(status().isUnauthorized());
    assertThat(sessions.findByPrincipalName(identity.id().toString())).isEmpty();
  }
}
