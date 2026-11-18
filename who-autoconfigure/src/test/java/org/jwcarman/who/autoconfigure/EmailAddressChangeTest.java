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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.autoconfigure.EmailLinkSignInTest.Browser;
import org.jwcarman.who.autoconfigure.EmailLinkSignInTest.MutableClock;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.IdentityStatus;
import org.jwcarman.who.core.email.WhoEmail;
import org.jwcarman.who.core.email.WhoEmailSender;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.core.spi.PermissionsResolver;
import org.jwcarman.who.emaillink.EmailAddressChangedMessage;
import org.jwcarman.who.emaillink.EmailAddressChanges;
import org.jwcarman.who.emaillink.EmailAddressConfirmationMessage;
import org.jwcarman.who.emaillink.EmailCredential;
import org.jwcarman.who.emaillink.EmailCredentialService;
import org.jwcarman.who.emaillink.EmailLinkMessage;
import org.jwcarman.who.spring.security.WhoWebSecurity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
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
import org.springframework.web.context.WebApplicationContext;

/**
 * Adding and changing an email address through {@code POST /who/me/email-addresses} and {@code POST
 * /who/me/email-addresses/confirmations}, on who's autoconfiguration, a browser chain and
 * PostgreSQL.
 */
@SpringBootTest(
    classes = EmailAddressChangeTest.App.class,
    properties = {
      "who.initialize-schema=always",
      "who.email-link.link-base-url=" + EmailLinkSignInTest.LINK_BASE_URL,
      "who.email-link.address-confirmation-url=" + EmailAddressChangeTest.PROFILE_URL,
      "who.email-link.code-secret=" + EmailLinkSignInTest.CODE_SECRET,
      "who.passkey.enabled=false"
    })
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class EmailAddressChangeTest {

  static final String PROFILE_URL = "https://app.example.com/profile";
  private static final String ALICE = "alice@example.com";
  private static final String NEW = "alice.new@example.com";

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
      return new MutableClock(EmailLinkSignInTest.NOW);
    }

    @Bean
    Outbox outbox() {
      return new Outbox();
    }

    @Bean
    WhoEmailSender whoEmailSender(Outbox outbox) {
      return email -> outbox.sent.add(email.source());
    }

    @Bean
    Executor whoEmailLinkExecutor() {
      return Runnable::run;
    }

    @Bean
    @Primary
    PermissionsResolver stubPermissionsResolver() {
      return id -> Set.of();
    }

    @Bean
    SecurityFilterChain browserChain(HttpSecurity http) {
      http.authorizeHttpRequests(a -> a.anyRequest().authenticated())
          .with(WhoWebSecurity.passwordless(), withDefaults());
      return http.build();
    }
  }

  static final class Outbox {
    final List<WhoEmail> sent = new CopyOnWriteArrayList<>();

    <T extends WhoEmail> List<T> of(Class<T> kind) {
      return sent.stream().filter(kind::isInstance).map(kind::cast).toList();
    }
  }

  @Autowired private WebApplicationContext context;
  @Autowired private JdbcClient jdbcClient;
  @Autowired private MutableClock clock;
  @Autowired private Outbox outbox;
  @Autowired private IdentityRepository identityRepository;
  @Autowired private EmailCredentialService emailCredentialService;

  private MockMvc mvc;
  private Identity alice;
  private EmailCredential alices;

  @BeforeEach
  void setUp() {
    jdbcClient.sql("DELETE FROM who_email_address_request").update();
    jdbcClient.sql("DELETE FROM who_email_link_token").update();
    jdbcClient.sql("DELETE FROM who_email_credential").update();
    jdbcClient.sql("DELETE FROM who_credential_identity").update();
    jdbcClient.sql("DELETE FROM who_identity").update();
    clock.set(EmailLinkSignInTest.NOW);
    outbox.sent.clear();
    alice = identityRepository.save(Identity.create(IdentityStatus.ACTIVE));
    alices = emailCredentialService.register(alice, ALICE);
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  private static MockHttpServletRequestBuilder json(String url, String body) {
    return post(url).contentType(MediaType.APPLICATION_JSON).content(body);
  }

  /** A browser signed in by an email link to the address, as a person would be. */
  private Browser signedIn(String address) throws Exception {
    Browser browser = new Browser(mvc);
    assertThat(
            browser
                .performWithCsrf(json("/who/email/send", "{\"email\":\"" + address + "\"}"))
                .getResponse()
                .getStatus())
        .isEqualTo(202);
    String link = outbox.of(EmailLinkMessage.class).getLast().link();
    String token = link.substring(link.indexOf("#token=") + "#token=".length());
    assertThat(
            browser
                .performWithCsrf(json("/who/email/sign-in", "{\"token\":\"" + token + "\"}"))
                .getResponse()
                .getStatus())
        .isEqualTo(200);
    return browser;
  }

  private static MvcResult ask(Browser browser, String body) throws Exception {
    return browser.performWithCsrf(json("/who/me/email-addresses", body));
  }

  private static MvcResult confirm(Browser browser, String body) throws Exception {
    return browser.performWithCsrf(json("/who/me/email-addresses/confirmations", body));
  }

  private EmailAddressConfirmationMessage confirmation() {
    return outbox.of(EmailAddressConfirmationMessage.class).getLast();
  }

  private String token() {
    String link = confirmation().link();
    return link.substring(
        link.indexOf(EmailAddressChanges.TOKEN_FRAGMENT)
            + EmailAddressChanges.TOKEN_FRAGMENT.length());
  }

  private boolean signsIn(String address) throws Exception {
    int before = outbox.of(EmailLinkMessage.class).size();
    new Browser(mvc).performWithCsrf(json("/who/email/send", "{\"email\":\"" + address + "\"}"));
    return outbox.of(EmailLinkMessage.class).size() > before;
  }

  @Test
  void old_path_is_not_served() throws Exception {
    Browser browser = signedIn(ALICE);

    assertThat(
            browser
                .performWithCsrf(json("/who/email/addresses", "{\"address\":\"" + NEW + "\"}"))
                .getResponse()
                .getStatus())
        .isEqualTo(404);
    assertThat(
            browser
                .performWithCsrf(json("/who/email/addresses/confirm", "{\"code\":\"000000\"}"))
                .getResponse()
                .getStatus())
        .isEqualTo(404);
  }

  @Nested
  class Adding_an_address {

    @Test
    void takes_the_link_sent_to_it() throws Exception {
      Browser browser = signedIn(ALICE);

      assertThat(ask(browser, "{\"address\":\"" + NEW + "\"}").getResponse().getStatus())
          .isEqualTo(202);
      assertThat(confirmation().address()).isEqualTo(NEW);
      assertThat(confirmation().link()).startsWith(PROFILE_URL + "#confirm-address=");
      assertThat(signsIn(NEW)).isFalse();

      MvcResult confirmed = confirm(browser, "{\"token\":\"" + token() + "\"}");

      assertThat(confirmed.getResponse().getStatus()).isEqualTo(200);
      assertThat(confirmed.getResponse().getContentAsString()).contains("credentialId");
      assertThat(signsIn(NEW)).isTrue();
      assertThat(signsIn(ALICE)).isTrue();
    }

    @Test
    void takes_the_code_too() throws Exception {
      Browser browser = signedIn(ALICE);
      ask(browser, "{\"address\":\"" + NEW + "\"}");

      MvcResult confirmed =
          confirm(browser, "{\"code\":\"" + confirmation().formattedCode() + "\"}");

      assertThat(confirmed.getResponse().getStatus()).isEqualTo(200);
      assertThat(signsIn(NEW)).isTrue();
    }

    @Test
    void someone_elses_address_answers_alike_and_sends_nothing() throws Exception {
      Identity bob = identityRepository.save(Identity.create(IdentityStatus.ACTIVE));
      emailCredentialService.register(bob, "bob@example.com");
      Browser browser = signedIn(ALICE);

      MvcResult result = ask(browser, "{\"address\":\"bob@example.com\"}");

      assertThat(result.getResponse().getStatus()).isEqualTo(202);
      assertThat(result.getResponse().getContentAsString()).isEmpty();
      assertThat(outbox.of(EmailAddressConfirmationMessage.class)).isEmpty();
    }

    @Test
    void cannot_be_confirmed_by_someone_else_signed_in() throws Exception {
      Identity bob = identityRepository.save(Identity.create(IdentityStatus.ACTIVE));
      emailCredentialService.register(bob, "bob@example.com");
      Browser alicesBrowser = signedIn(ALICE);
      ask(alicesBrowser, "{\"address\":\"" + NEW + "\"}");
      String token = token();
      Browser bobsBrowser = signedIn("bob@example.com");

      assertThat(confirm(bobsBrowser, "{\"token\":\"" + token + "\"}").getResponse().getStatus())
          .isEqualTo(400);
      assertThat(signsIn(NEW)).isFalse();
      assertThat(confirm(alicesBrowser, "{\"token\":\"" + token + "\"}").getResponse().getStatus())
          .isEqualTo(200);
    }

    @Test
    void an_address_taken_since_is_409() throws Exception {
      Browser browser = signedIn(ALICE);
      ask(browser, "{\"address\":\"" + NEW + "\"}");
      emailCredentialService.register(
          identityRepository.save(Identity.create(IdentityStatus.ACTIVE)), NEW);

      assertThat(confirm(browser, "{\"token\":\"" + token() + "\"}").getResponse().getStatus())
          .isEqualTo(409);
    }

    @Test
    void a_wrong_or_missing_proof_is_400() throws Exception {
      Browser browser = signedIn(ALICE);
      ask(browser, "{\"address\":\"" + NEW + "\"}");

      assertThat(confirm(browser, "{\"token\":\"nope\"}").getResponse().getStatus()).isEqualTo(400);
      assertThat(confirm(browser, "{}").getResponse().getStatus()).isEqualTo(400);
      assertThat(
              confirm(browser, "{\"token\":\"" + token() + "\",\"code\":\"AAAAAA\"}")
                  .getResponse()
                  .getStatus())
          .isEqualTo(400);
      assertThat(signsIn(NEW)).isFalse();
    }
  }

  @Nested
  class Changing_an_address {

    @Test
    void replaces_the_old_one_and_tells_it() throws Exception {
      Browser browser = signedIn(ALICE);

      assertThat(
              ask(browser, "{\"address\":\"" + NEW + "\",\"replaces\":\"" + alices.id() + "\"}")
                  .getResponse()
                  .getStatus())
          .isEqualTo(202);
      assertThat(confirmation().change()).isTrue();
      assertThat(confirm(browser, "{\"token\":\"" + token() + "\"}").getResponse().getStatus())
          .isEqualTo(200);

      assertThat(signsIn(NEW)).isTrue();
      assertThat(signsIn(ALICE)).isFalse();
      assertThat(outbox.of(EmailAddressChangedMessage.class))
          .containsExactly(new EmailAddressChangedMessage(ALICE, NEW));
    }

    @Test
    void of_someone_elses_address_is_404() throws Exception {
      Identity bob = identityRepository.save(Identity.create(IdentityStatus.ACTIVE));
      EmailCredential bobs = emailCredentialService.register(bob, "bob@example.com");
      Browser browser = signedIn(ALICE);

      assertThat(
              ask(browser, "{\"address\":\"" + NEW + "\",\"replaces\":\"" + bobs.id() + "\"}")
                  .getResponse()
                  .getStatus())
          .isEqualTo(404);
      assertThat(
              ask(browser, "{\"address\":\"" + NEW + "\",\"replaces\":\"not-an-id\"}")
                  .getResponse()
                  .getStatus())
          .isEqualTo(404);
      assertThat(outbox.of(EmailAddressConfirmationMessage.class)).isEmpty();
    }
  }

  @Nested
  class Both_endpoints {

    @Test
    void signed_out_are_401() throws Exception {
      Browser browser = new Browser(mvc);

      assertThat(ask(browser, "{\"address\":\"" + NEW + "\"}").getResponse().getStatus())
          .isEqualTo(401);
      assertThat(confirm(browser, "{\"code\":\"AAAAAA\"}").getResponse().getStatus())
          .isEqualTo(401);
      assertThat(outbox.of(EmailAddressConfirmationMessage.class)).isEmpty();
    }

    @Test
    void without_csrf_are_403() throws Exception {
      Browser browser = signedIn(ALICE);

      assertThat(
              browser
                  .perform(json("/who/me/email-addresses", "{\"address\":\"" + NEW + "\"}"))
                  .getResponse()
                  .getStatus())
          .isEqualTo(403);
      assertThat(outbox.of(EmailAddressConfirmationMessage.class)).isEmpty();
    }

    @Test
    void long_after_signing_in_require_signing_in_again() throws Exception {
      Browser browser = signedIn(ALICE);
      clock.advance(Duration.ofMinutes(11));

      assertThat(ask(browser, "{\"address\":\"" + NEW + "\"}").getResponse().getStatus())
          .isEqualTo(401);
      assertThat(outbox.of(EmailAddressConfirmationMessage.class)).isEmpty();
    }

    @Test
    void a_confirmation_long_after_signing_in_requires_signing_in_again() throws Exception {
      Browser browser = signedIn(ALICE);
      clock.advance(Duration.ofMinutes(9));
      ask(browser, "{\"address\":\"" + NEW + "\"}");
      String token = token();
      clock.advance(Duration.ofMinutes(2));

      assertThat(confirm(browser, "{\"token\":\"" + token + "\"}").getResponse().getStatus())
          .isEqualTo(401);
      assertThat(signsIn(NEW)).isFalse();
    }
  }
}
