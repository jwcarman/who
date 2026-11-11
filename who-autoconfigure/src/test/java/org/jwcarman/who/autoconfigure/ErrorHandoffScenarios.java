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

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.BeforeEach;
import org.jwcarman.who.autoconfigure.EmailLinkSignInTest.MutableClock;
import org.jwcarman.who.autoconfigure.EmailLinkSignInTest.RecordingSender;
import org.jwcarman.who.autoconfigure.PasskeySignInTest.Browser;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.IdentityStatus;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.core.spi.PermissionsResolver;
import org.jwcarman.who.emaillink.EmailCredentialService;
import org.jwcarman.who.spring.security.RequiresPermission;
import org.jwcarman.who.spring.security.WhoWebSecurity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

/**
 * Every error path who used to answer itself, driven through who's autoconfiguration and a browser
 * chain. {@link ErrorHandoffTest} runs them with an application advice, {@link
 * ErrorHandoffWithoutAdviceTest} without one.
 */
abstract class ErrorHandoffScenarios {

  static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");
  static final String ORIGIN = "https://app.example.com";
  static final String OWNER = "owner@example.com";

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", EmailLinkSignInTest.POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", EmailLinkSignInTest.POSTGRES::getUsername);
    registry.add("spring.datasource.password", EmailLinkSignInTest.POSTGRES::getPassword);
  }

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  static class App {

    @Bean
    MutableClock clock() {
      return new MutableClock(NOW);
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
    GuardedController guardedController() {
      return new GuardedController();
    }

    @Bean
    SecurityFilterChain browserChain(HttpSecurity http) {
      http.authorizeHttpRequests(
              a ->
                  a.requestMatchers("/api/admin")
                      .hasAuthority("admin")
                      .anyRequest()
                      .authenticated())
          .with(WhoWebSecurity.passwordless(), withDefaults());
      return http.build();
    }
  }

  @RestController
  static class GuardedController {

    @GetMapping("/api/guarded")
    @RequiresPermission("guarded.read")
    String guarded() {
      return "guarded";
    }

    @GetMapping("/api/admin")
    String admin() {
      return "admin";
    }
  }

  @Autowired private WebApplicationContext context;
  @Autowired private JdbcClient jdbcClient;
  @Autowired private MutableClock clock;
  @Autowired private RecordingSender sender;
  @Autowired private IdentityRepository identityRepository;
  @Autowired private EmailCredentialService emailCredentialService;

  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    jdbcClient.sql("DELETE FROM who_passkey_credential").update();
    jdbcClient.sql("DELETE FROM who_passkey_user").update();
    jdbcClient.sql("DELETE FROM who_email_link_token").update();
    jdbcClient.sql("DELETE FROM who_email_credential").update();
    jdbcClient.sql("DELETE FROM who_credential_identity").update();
    jdbcClient.sql("DELETE FROM who_identity").update();
    clock.set(NOW);
    sender.sent.clear();
    Identity owner = identityRepository.save(Identity.create(IdentityStatus.ACTIVE));
    emailCredentialService.register(owner, OWNER);
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request) {
    return request.contentType(MediaType.APPLICATION_JSON);
  }

  private Browser signedIn() throws Exception {
    Browser browser = new Browser(mvc);
    browser.performWithCsrf(json(post("/ott/generate")).content("{\"email\":\"" + OWNER + "\"}"));
    String token = sender.sent.getLast().token();
    browser.performWithCsrf(json(post("/login/ott")).content("{\"token\":\"" + token + "\"}"));
    assertThat(browser.me()).isEqualTo(200);
    return browser;
  }

  MvcResult badSignInToken() throws Exception {
    return new Browser(mvc)
        .performWithCsrf(
            json(post("/login/ott")).content("{\"token\":\"" + "x".repeat(43) + "\"}"));
  }

  MvcResult unknownPasskeySignIn() throws Exception {
    // the device creates a credential for real options, but it is never registered
    PasskeyDevice device = new PasskeyDevice(ORIGIN);
    Browser owner = signedIn();
    device.register(
        owner
            .performWithCsrf(post("/webauthn/register/options"))
            .getResponse()
            .getContentAsString(),
        "never sent");
    Browser browser = new Browser(mvc);
    String options =
        browser
            .performWithCsrf(post("/webauthn/authenticate/options"))
            .getResponse()
            .getContentAsString();
    return browser.performWithCsrf(json(post("/login/webauthn")).content(device.signIn(options)));
  }

  MvcResult signedOutGuarded() throws Exception {
    return new Browser(mvc).perform(get("/api/guarded"));
  }

  MvcResult signedOutWhoMe() throws Exception {
    return new Browser(mvc).perform(get("/who/me"));
  }

  MvcResult missingPermission() throws Exception {
    return signedIn().perform(get("/api/guarded"));
  }

  MvcResult deniedByUrlRule() throws Exception {
    return signedIn().perform(get("/api/admin"));
  }

  MvcResult registrationSignedOut() throws Exception {
    return new Browser(mvc).performWithCsrf(post("/webauthn/register/options"));
  }

  MvcResult registrationOutsideTheWindow() throws Exception {
    Browser browser = signedIn();
    clock.advance(Duration.ofMinutes(10).plusSeconds(1));
    return browser.performWithCsrf(post("/webauthn/register/options"));
  }

  MvcResult invalidRegistration() throws Exception {
    Browser browser = signedIn();
    String options =
        browser
            .performWithCsrf(post("/webauthn/register/options"))
            .getResponse()
            .getContentAsString();
    String body = new PasskeyDevice("https://evil.example.com").register(options, "x");
    return browser.performWithCsrf(json(post("/webauthn/register")).content(body));
  }
}
