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
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.IdentityStatus;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.emaillink.EmailCredentialService;
import org.jwcarman.who.spring.security.HumanCheckRequest;
import org.jwcarman.who.spring.security.WhoHumanCheck;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * A human check in front of the real email-link endpoints, under a non-default root: a refused
 * request sends no email and uses up no code.
 */
@SpringBootTest(
    classes = {EmailLinkSignInTest.App.class, EmailLinkHumanCheckTest.Check.class},
    properties = {
      "who.base-path=/auth",
      "who.email-link.link-base-url=" + EmailLinkSignInTest.LINK_BASE_URL,
      "who.email-link.code-secret=" + EmailLinkSignInTest.CODE_SECRET,
      "who.passkey.enabled=false"
    })
@Testcontainers
class EmailLinkHumanCheckTest {

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    EmailLinkSignInTest.configureProperties(registry);
  }

  static final AtomicBoolean PASS = new AtomicBoolean();
  static final List<HumanCheckRequest> SEEN = new CopyOnWriteArrayList<>();

  @Configuration(proxyBeanMethods = false)
  static class Check {
    @Bean
    WhoHumanCheck switchableCheck() {
      return request -> {
        SEEN.add(request);
        return PASS.get();
      };
    }
  }

  @Autowired private WebApplicationContext context;
  @Autowired private JdbcClient jdbcClient;
  @Autowired private EmailLinkSignInTest.RecordingSender sender;
  @Autowired private IdentityRepository identityRepository;
  @Autowired private EmailCredentialService emailCredentialService;

  private EmailLinkSignInTest.Browser browser;

  @BeforeEach
  void setUp() {
    jdbcClient.sql("DELETE FROM who_email_link_token").update();
    jdbcClient.sql("DELETE FROM who_email_credential").update();
    jdbcClient.sql("DELETE FROM who_credential_identity").update();
    jdbcClient.sql("DELETE FROM who_identity").update();
    sender.sent.clear();
    SEEN.clear();
    Identity identity = identityRepository.save(Identity.create(IdentityStatus.ACTIVE));
    emailCredentialService.register(identity, EmailLinkSignInTest.ADDRESS);
    browser =
        new EmailLinkSignInTest.Browser(
            MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build());
  }

  private static MockHttpServletRequestBuilder json(String path, String body) {
    return post(path)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body)
        .header(WhoHumanCheck.HEADER, "tok");
  }

  private MvcResult send() throws Exception {
    return browser.performWithCsrf(
        json("/auth/email/send", "{\"email\":\"" + EmailLinkSignInTest.ADDRESS + "\"}"));
  }

  private MvcResult signInWithCode(String code) throws Exception {
    return browser.performWithCsrf(
        json(
            "/auth/email/sign-in",
            "{\"email\":\"" + EmailLinkSignInTest.ADDRESS + "\",\"code\":\"" + code + "\"}"));
  }

  @Test
  void a_refused_send_sends_no_email() throws Exception {
    PASS.set(false);

    MvcResult result = send();

    assertThat(result.getResponse().getStatus()).isEqualTo(403);
    assertThat(result.getResponse().getHeader("Who-Error")).isEqualTo("human-check-failed");
    assertThat(sender.sent).isEmpty();
    assertThat(SEEN)
        .extracting(HumanCheckRequest::action, r -> r.token().orElseThrow())
        .containsExactly(tuple("email-send", "tok"));
  }

  @Test
  void a_passed_send_sends_the_email() throws Exception {
    PASS.set(true);

    assertThat(send().getResponse().getStatus()).isEqualTo(202);
    assertThat(sender.sent).hasSize(1);
  }

  @Test
  void a_refused_code_sign_in_uses_up_no_attempt() throws Exception {
    PASS.set(true);
    send();
    String code = sender.sent.getLast().code();

    PASS.set(false);
    for (int i = 0; i < 6; i++) {
      MvcResult refused = signInWithCode("WRONG0");
      assertThat(refused.getResponse().getHeader("Who-Error")).isEqualTo("human-check-failed");
    }

    PASS.set(true);
    assertThat(signInWithCode(code).getResponse().getStatus()).isEqualTo(200);
    assertThat(SEEN).extracting(HumanCheckRequest::action).contains("email-sign-in");
  }
}
