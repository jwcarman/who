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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.autoconfigure.EmailLinkSignInTest.Browser;
import org.jwcarman.who.autoconfigure.EmailLinkSignInTest.MutableClock;
import org.jwcarman.who.autoconfigure.EmailLinkSignInTest.RecordingSender;
import org.jwcarman.who.autoconfigure.EmailLinkSignInTest.SentLink;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.IdentityStatus;
import org.jwcarman.who.core.event.SignInFailed;
import org.jwcarman.who.core.event.SignInFailureReason;
import org.jwcarman.who.core.event.SignInMethod;
import org.jwcarman.who.core.event.SignInSucceeded;
import org.jwcarman.who.core.event.WhoEvent;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.core.spi.PermissionsResolver;
import org.jwcarman.who.emaillink.EmailCodeAttempt;
import org.jwcarman.who.emaillink.EmailCredential;
import org.jwcarman.who.emaillink.EmailCredentialService;
import org.jwcarman.who.emaillink.EmailLinkAuthenticationProvider;
import org.jwcarman.who.emaillink.EmailLinkOutcome;
import org.jwcarman.who.emaillink.JdbcEmailLinkTokenRepository;
import org.jwcarman.who.emaillink.SignInEmailRequested;
import org.jwcarman.who.spring.security.WhoSession;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.jwcarman.who.spring.security.WhoWebSecurity;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
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
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * The events an email sign-in publishes, through who's autoconfiguration, a browser chain that
 * applies {@code WhoWebSecurity.passwordless()}, and PostgreSQL.
 */
@SpringBootTest(
    classes = EmailSignInEventsTest.App.class,
    properties = {
      "who.initialize-schema=always",
      "who.email-link.link-base-url=" + EmailLinkSignInTest.LINK_BASE_URL,
      "who.email-link.code-secret=" + EmailLinkSignInTest.CODE_SECRET
    })
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class EmailSignInEventsTest {

  static final String ADDRESS = EmailLinkSignInTest.ADDRESS;
  static final String STRANGER = "stranger@example.com";

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", EmailLinkSignInTest.POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", EmailLinkSignInTest.POSTGRES::getUsername);
    registry.add("spring.datasource.password", EmailLinkSignInTest.POSTGRES::getPassword);
  }

  // ---------------------------------------------------------------- test application

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  static class App {

    @Bean
    MutableClock clock() {
      return new MutableClock(EmailLinkSignInTest.NOW);
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
    RecordingListener recordingListener() {
      return new RecordingListener();
    }

    @Bean
    RacingTokenRepository racingTokenRepository(JdbcClient jdbcClient) {
      return new RacingTokenRepository(jdbcClient);
    }

    @Bean
    SecurityFilterChain browserChain(HttpSecurity http) {
      http.authorizeHttpRequests(a -> a.anyRequest().authenticated())
          .with(WhoWebSecurity.passwordless(), withDefaults());
      return http.build();
    }
  }

  /** Records every who event, and what the session held when a sign-in succeeded. */
  static final class RecordingListener {

    final List<WhoEvent> events = new CopyOnWriteArrayList<>();
    final List<Optional<UUID>> sessionIdentityAtSuccess = new CopyOnWriteArrayList<>();
    final List<SignInFailed> thrownOn = new CopyOnWriteArrayList<>();
    volatile boolean throwOnFailure;

    @EventListener
    void onEvent(WhoEvent event) {
      events.add(event);
      if (event instanceof SignInSucceeded) {
        sessionIdentityAtSuccess.add(sessionIdentity());
      }
    }

    @EventListener
    void explode(SignInFailed event) {
      if (throwOnFailure) {
        thrownOn.add(event);
        throw new IllegalStateException("listener failed");
      }
    }

    private static Optional<UUID> sessionIdentity() {
      RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
      if (attributes == null) {
        return Optional.empty();
      }
      Object session =
          attributes.getAttribute(
              WhoSessionSecurityContextRepository.SESSION_ATTRIBUTE,
              RequestAttributes.SCOPE_SESSION);
      return session instanceof WhoSession who ? Optional.of(who.identityId()) : Optional.empty();
    }

    <T extends WhoEvent> List<T> of(Class<T> type) {
      return events.stream().filter(type::isInstance).map(type::cast).toList();
    }
  }

  /** Can make a matched code lose the race to a concurrent use, as a second request would. */
  static final class RacingTokenRepository extends JdbcEmailLinkTokenRepository {

    private final JdbcClient jdbcClient;
    volatile boolean loseTheRace;

    RacingTokenRepository(JdbcClient jdbcClient) {
      super(jdbcClient);
      this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<EmailCodeAttempt> attemptCode(
        String normalizedAddress, int maxAttempts, Instant now) {
      Optional<EmailCodeAttempt> attempt = super.attemptCode(normalizedAddress, maxAttempts, now);
      if (loseTheRace) {
        jdbcClient.sql("DELETE FROM who_email_link_token").update();
      }
      return attempt;
    }
  }

  // ---------------------------------------------------------------- fixtures

  @Autowired private WebApplicationContext context;
  @Autowired private JdbcClient jdbcClient;
  @Autowired private MutableClock clock;
  @Autowired private RecordingSender sender;
  @Autowired private RecordingListener listener;
  @Autowired private RacingTokenRepository tokenRepository;
  @Autowired private IdentityRepository identityRepository;
  @Autowired private EmailCredentialService emailCredentialService;

  private MockMvc mvc;
  private Identity identity;
  private EmailCredential credential;

  @BeforeEach
  void setUp() {
    jdbcClient.sql("DELETE FROM who_email_link_token").update();
    jdbcClient.sql("DELETE FROM who_email_credential").update();
    jdbcClient.sql("DELETE FROM who_credential_identity").update();
    jdbcClient.sql("DELETE FROM who_identity").update();
    clock.set(EmailLinkSignInTest.NOW);
    sender.sent.clear();
    listener.throwOnFailure = false;
    listener.thrownOn.clear();
    tokenRepository.loseTheRace = false;

    identity = identityRepository.save(Identity.create(IdentityStatus.ACTIVE));
    credential = emailCredentialService.register(identity, ADDRESS);
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    listener.events.clear();
    listener.sessionIdentityAtSuccess.clear();
  }

  private static MockHttpServletRequestBuilder generate(String address) {
    return post("/ott/generate")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"email\":\"" + address + "\"}");
  }

  private static MockHttpServletRequestBuilder confirmBody(String body) {
    return post("/login/ott").contentType(MediaType.APPLICATION_JSON).content(body);
  }

  private static MockHttpServletRequestBuilder confirm(String token) {
    return confirmBody("{\"token\":\"" + token + "\"}");
  }

  private static MockHttpServletRequestBuilder confirmCode(String address, String code) {
    return confirmBody("{\"email\":\"" + address + "\",\"code\":\"" + code + "\"}");
  }

  /** Requests a link and returns what was sent, clearing the events the request published. */
  private SentLink requestLink(Browser browser) throws Exception {
    MvcResult result = browser.performWithCsrf(generate(ADDRESS));
    assertThat(result.getResponse().getStatus()).isEqualTo(202);
    listener.events.clear();
    return sender.sent.getLast();
  }

  private static String wrongCode(String code) {
    return code.startsWith("0") ? "111111" : "000000";
  }

  /** The uniform failure answer: {@code 401} with no body, whatever the reason. */
  private static void assertUniform401(MvcResult result) throws Exception {
    assertThat(result.getResponse().getStatus()).isEqualTo(401);
    assertThat(result.getResponse().getContentAsString()).isEmpty();
  }

  private SignInFailed onlyFailure() {
    assertThat(listener.of(SignInSucceeded.class)).isEmpty();
    List<SignInFailed> failures = listener.of(SignInFailed.class);
    assertThat(failures).hasSize(1);
    SignInFailed failure = failures.getFirst();
    assertThat(failure.occurredAt()).isEqualTo(clock.instant());
    return failure;
  }

  private void assertFailure(
      SignInMethod method,
      SignInFailureReason reason,
      String address,
      UUID identityId,
      UUID credentialId) {
    assertThat(onlyFailure())
        .isEqualTo(
            new SignInFailed(clock.instant(), method, reason, address, identityId, credentialId));
  }

  private int codeAttempts() {
    return jdbcClient
        .sql("SELECT code_attempts FROM who_email_link_token WHERE credential_id = :id")
        .param("id", credential.id())
        .query(Integer.class)
        .single();
  }

  // ---------------------------------------------------------------- success

  @Nested
  class When_the_sign_in_succeeds {

    @Test
    void a_link_sign_in_publishes_sign_in_succeeded_after_the_session_is_saved() throws Exception {
      Browser browser = new Browser(mvc);
      SentLink sent = requestLink(browser);

      assertThat(browser.performWithCsrf(confirm(sent.token())).getResponse().getStatus())
          .isEqualTo(200);

      assertThat(listener.events)
          .containsExactly(
              new SignInSucceeded(
                  clock.instant(), SignInMethod.EMAIL_LINK, identity.id(), credential.id()));
      // the session already held the sign-in when the event was published
      assertThat(listener.sessionIdentityAtSuccess).containsExactly(Optional.of(identity.id()));
      MvcResult me = browser.perform(get("/who/me"));
      assertThat(me.getResponse().getStatus()).isEqualTo(200);
      assertThat(me.getResponse().getContentAsString()).contains(identity.id().toString());
    }

    @Test
    void a_code_sign_in_publishes_email_code_success() throws Exception {
      Browser browser = new Browser(mvc);
      SentLink sent = requestLink(browser);

      assertThat(
              browser.performWithCsrf(confirmCode(ADDRESS, sent.code())).getResponse().getStatus())
          .isEqualTo(200);

      assertThat(listener.events)
          .containsExactly(
              new SignInSucceeded(
                  clock.instant(), SignInMethod.EMAIL_CODE, identity.id(), credential.id()));
      assertThat(listener.sessionIdentityAtSuccess).containsExactly(Optional.of(identity.id()));
    }
  }

  // ---------------------------------------------------------------- link failures

  @Nested
  class When_a_link_sign_in_fails {

    @Test
    void a_request_with_no_usable_proof_is_malformed() throws Exception {
      assertUniform401(new Browser(mvc).performWithCsrf(confirmBody("{}")));

      assertFailure(SignInMethod.EMAIL_LINK, SignInFailureReason.MALFORMED, null, null, null);
    }

    @Test
    void an_unknown_link_is_invalid_link() throws Exception {
      assertUniform401(new Browser(mvc).performWithCsrf(confirm("no-such-token")));

      assertFailure(SignInMethod.EMAIL_LINK, SignInFailureReason.INVALID_LINK, null, null, null);
    }

    @Test
    void an_expired_link_is_invalid_link() throws Exception {
      Browser browser = new Browser(mvc);
      SentLink sent = requestLink(browser);
      clock.advance(Duration.ofMinutes(5).plusSeconds(1));

      assertUniform401(browser.performWithCsrf(confirm(sent.token())));

      assertFailure(SignInMethod.EMAIL_LINK, SignInFailureReason.INVALID_LINK, null, null, null);
    }

    @Test
    void a_link_of_an_inactive_identity_is_inactive_identity() throws Exception {
      Browser browser = new Browser(mvc);
      SentLink sent = requestLink(browser);
      identityRepository.save(identity.withStatus(IdentityStatus.SUSPENDED));

      assertUniform401(browser.performWithCsrf(confirm(sent.token())));

      assertFailure(
          SignInMethod.EMAIL_LINK,
          SignInFailureReason.INACTIVE_IDENTITY,
          null,
          identity.id(),
          credential.id());
    }

    @Test
    void a_link_whose_credential_is_no_longer_linked_is_invalid_link() throws Exception {
      Browser browser = new Browser(mvc);
      SentLink sent = requestLink(browser);
      jdbcClient.sql("DELETE FROM who_credential_identity").update();

      assertUniform401(browser.performWithCsrf(confirm(sent.token())));

      assertFailure(
          SignInMethod.EMAIL_LINK, SignInFailureReason.INVALID_LINK, null, null, credential.id());
    }
  }

  // ---------------------------------------------------------------- code failures

  @Nested
  class When_a_code_sign_in_fails {

    @Test
    void a_code_of_the_wrong_shape_is_malformed() throws Exception {
      assertUniform401(new Browser(mvc).performWithCsrf(confirmCode(ADDRESS, "!!!!!!")));

      assertFailure(SignInMethod.EMAIL_CODE, SignInFailureReason.MALFORMED, ADDRESS, null, null);
    }

    @Test
    void an_unsafe_address_fails_as_malformed_with_no_address() throws Exception {
      Browser browser = new Browser(mvc);
      String tooLong = "a".repeat(309) + "@example.com";
      assertThat(tooLong).hasSize(321);

      for (String address : List.of("owner\\u0001@example.com", tooLong)) {
        listener.events.clear();
        assertUniform401(browser.performWithCsrf(confirmCode(address, "000000")));

        assertFailure(SignInMethod.EMAIL_CODE, SignInFailureReason.MALFORMED, null, null, null);
      }
    }

    @Test
    void an_unknown_address_is_no_live_code() throws Exception {
      assertUniform401(new Browser(mvc).performWithCsrf(confirmCode(STRANGER, "000000")));

      assertFailure(
          SignInMethod.EMAIL_CODE, SignInFailureReason.NO_LIVE_CODE, STRANGER, null, null);
    }

    @Test
    void the_address_is_reported_normalized() throws Exception {
      assertUniform401(
          new Browser(mvc).performWithCsrf(confirmCode(" Stranger@Example.COM ", "000000")));

      assertFailure(
          SignInMethod.EMAIL_CODE, SignInFailureReason.NO_LIVE_CODE, STRANGER, null, null);
    }

    @Test
    void a_wrong_code_is_wrong_code() throws Exception {
      Browser browser = new Browser(mvc);
      SentLink sent = requestLink(browser);

      assertUniform401(browser.performWithCsrf(confirmCode(ADDRESS, wrongCode(sent.code()))));

      assertFailure(
          SignInMethod.EMAIL_CODE, SignInFailureReason.WRONG_CODE, ADDRESS, null, credential.id());
    }

    @Test
    void a_right_code_that_loses_the_race_is_code_already_used() throws Exception {
      Browser browser = new Browser(mvc);
      SentLink sent = requestLink(browser);
      tokenRepository.loseTheRace = true;

      assertUniform401(browser.performWithCsrf(confirmCode(ADDRESS, sent.code())));

      assertFailure(
          SignInMethod.EMAIL_CODE,
          SignInFailureReason.CODE_ALREADY_USED,
          ADDRESS,
          null,
          credential.id());
    }

    @Test
    void a_code_of_an_inactive_identity_is_inactive_identity() throws Exception {
      Browser browser = new Browser(mvc);
      SentLink sent = requestLink(browser);
      identityRepository.save(identity.withStatus(IdentityStatus.SUSPENDED));

      assertUniform401(browser.performWithCsrf(confirmCode(ADDRESS, sent.code())));

      assertFailure(
          SignInMethod.EMAIL_CODE,
          SignInFailureReason.INACTIVE_IDENTITY,
          ADDRESS,
          identity.id(),
          credential.id());
    }

    @Test
    void a_code_whose_credential_is_no_longer_linked_is_no_live_code() throws Exception {
      Browser browser = new Browser(mvc);
      SentLink sent = requestLink(browser);
      jdbcClient.sql("DELETE FROM who_credential_identity").update();

      assertUniform401(browser.performWithCsrf(confirmCode(ADDRESS, sent.code())));

      assertFailure(
          SignInMethod.EMAIL_CODE,
          SignInFailureReason.NO_LIVE_CODE,
          ADDRESS,
          null,
          credential.id());
    }
  }

  // ---------------------------------------------------------------- listeners and logs

  @Nested
  class When_a_listener_throws {

    @Test
    void a_throwing_listener_leaves_the_401_and_the_attempt_count() throws Exception {
      Browser browser = new Browser(mvc);
      SentLink sent = requestLink(browser);
      listener.throwOnFailure = true;

      assertUniform401(browser.performWithCsrf(confirmCode(ADDRESS, wrongCode(sent.code()))));

      // the listener did receive the failure and threw on it
      assertThat(listener.thrownOn)
          .singleElement()
          .extracting(SignInFailed::reason)
          .isEqualTo(SignInFailureReason.WRONG_CODE);
      assertThat(codeAttempts()).isEqualTo(1);
    }
  }

  @Nested
  class When_a_sign_in_fails_the_log {

    private final Logger logger =
        (Logger) LoggerFactory.getLogger(EmailLinkAuthenticationProvider.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Level previousLevel;

    @BeforeEach
    void captureLogs() {
      previousLevel = logger.getLevel();
      logger.setLevel(Level.DEBUG);
      logs.start();
      logger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
      logger.detachAppender(logs);
      logger.setLevel(previousLevel);
    }

    @Test
    void has_one_debug_line_with_the_reason_and_the_address_only_at_debug() throws Exception {
      Browser browser = new Browser(mvc);
      SentLink sent = requestLink(browser);

      assertUniform401(browser.performWithCsrf(confirmCode(ADDRESS, wrongCode(sent.code()))));

      assertThat(logs.list).hasSize(1);
      ILoggingEvent line = logs.list.getFirst();
      assertThat(line.getLevel()).isEqualTo(Level.DEBUG);
      assertThat(line.getFormattedMessage())
          .contains("EMAIL_CODE")
          .contains("WRONG_CODE")
          .contains(credential.id().toString())
          .contains(ADDRESS)
          .doesNotContain(sent.code());
    }
  }

  // ---------------------------------------------------------------- sign-in emails

  @Nested
  class When_a_sign_in_email_is_requested {

    @Test
    void requesting_a_link_publishes_sign_in_email_requested_with_its_outcome() throws Exception {
      Browser browser = new Browser(mvc);

      browser.performWithCsrf(generate(ADDRESS));
      browser.performWithCsrf(generate(STRANGER));
      clock.advance(Duration.ofSeconds(30));
      browser.performWithCsrf(generate(ADDRESS));

      assertThat(listener.events)
          .containsExactly(
              new SignInEmailRequested(
                  EmailLinkSignInTest.NOW, ADDRESS, EmailLinkOutcome.SENT, identity.id()),
              new SignInEmailRequested(
                  EmailLinkSignInTest.NOW, STRANGER, EmailLinkOutcome.UNKNOWN_ADDRESS, null),
              new SignInEmailRequested(
                  clock.instant(), ADDRESS, EmailLinkOutcome.RESEND_SUPPRESSED, identity.id()));
    }

    @Test
    void an_inactive_identity_is_reported_with_its_id() throws Exception {
      identityRepository.save(identity.withStatus(IdentityStatus.SUSPENDED));

      new Browser(mvc).performWithCsrf(generate(ADDRESS));

      assertThat(listener.events)
          .containsExactly(
              new SignInEmailRequested(
                  clock.instant(), ADDRESS, EmailLinkOutcome.INACTIVE_IDENTITY, identity.id()));
    }

    @Test
    void an_unsafe_or_missing_address_is_reported_with_no_address() throws Exception {
      Browser browser = new Browser(mvc);

      browser.performWithCsrf(generate("owner\\u0001@example.com"));
      browser.performWithCsrf(
          post("/ott/generate").contentType(MediaType.APPLICATION_JSON).content("{}"));

      SignInEmailRequested unknown =
          new SignInEmailRequested(clock.instant(), null, EmailLinkOutcome.UNKNOWN_ADDRESS, null);
      assertThat(listener.events).containsExactly(unknown, unknown);
    }
  }
}
