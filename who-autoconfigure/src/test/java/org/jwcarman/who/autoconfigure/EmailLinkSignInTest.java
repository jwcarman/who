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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.IdentityStatus;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.core.spi.PermissionsResolver;
import org.jwcarman.who.emaillink.EmailCredential;
import org.jwcarman.who.emaillink.EmailCredentialService;
import org.jwcarman.who.emaillink.EmailLinkIssuer;
import org.jwcarman.who.emaillink.EmailLinkLoginMethod;
import org.jwcarman.who.emaillink.EmailLinkSender;
import org.jwcarman.who.spring.security.WhoWebSecurity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
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
 * The whole email-link sign-in flow, through who's autoconfiguration, a browser chain that applies
 * {@code WhoWebSecurity.passwordless()}, and PostgreSQL.
 */
@SpringBootTest(
    classes = EmailLinkSignInTest.App.class,
    properties = {
      "who.initialize-schema=always",
      "who.email-link.link-base-url=" + EmailLinkSignInTest.LINK_BASE_URL
    })
@Testcontainers
class EmailLinkSignInTest {

  static final String LINK_BASE_URL = "https://app.example.com/signin";
  static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");
  static final String ADDRESS = "owner@example.com";
  static final String XSRF_COOKIE = "XSRF-TOKEN";
  static final String XSRF_HEADER = "X-XSRF-TOKEN";

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

  // ---------------------------------------------------------------- test application

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
    SecurityFilterChain browserChain(HttpSecurity http) {
      http.authorizeHttpRequests(a -> a.anyRequest().authenticated())
          .with(WhoWebSecurity.passwordless(), withDefaults());
      return http.build();
    }
  }

  static final class MutableClock extends Clock {

    private final AtomicReference<Instant> now;

    MutableClock(Instant start) {
      this.now = new AtomicReference<>(start);
    }

    void set(Instant instant) {
      now.set(instant);
    }

    void advance(Duration duration) {
      now.updateAndGet(i -> i.plus(duration));
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now.get();
    }
  }

  record SentLink(String address, String link) {
    String token() {
      return link.substring(link.indexOf("#token=") + "#token=".length());
    }
  }

  static final class RecordingSender implements EmailLinkSender {

    final List<SentLink> sent = new CopyOnWriteArrayList<>();

    @Override
    public void send(String address, String link) {
      sent.add(new SentLink(address, link));
    }
  }

  /** What a browser keeps between requests: its session and the script-readable CSRF cookie. */
  static final class Browser {

    private final MockMvc mvc;
    final MockHttpSession session = new MockHttpSession();
    String xsrf;

    Browser(MockMvc mvc) {
      this.mvc = mvc;
    }

    MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
      // as a servlet container does when the browser presents its session cookie
      request
          .session(session)
          .with(
              r -> {
                r.setRequestedSessionId(session.getId());
                r.setRequestedSessionIdValid(true);
                return r;
              });
      if (xsrf != null) {
        request.cookie(new Cookie(XSRF_COOKIE, xsrf));
      }
      MvcResult result = mvc.perform(request).andReturn();
      remember(result.getResponse());
      return result;
    }

    /** Sends the request with the CSRF cookie echoed in the header, as an SPA does. */
    MvcResult performWithCsrf(MockHttpServletRequestBuilder request) throws Exception {
      if (xsrf == null) {
        perform(get("/who/me"));
      }
      assertThat(xsrf).isNotBlank();
      return perform(request.header(XSRF_HEADER, xsrf));
    }

    private void remember(MockHttpServletResponse response) {
      List<Cookie> set =
          Arrays.stream(response.getCookies())
              .filter(c -> XSRF_COOKIE.equals(c.getName()))
              .toList();
      if (!set.isEmpty()) {
        Cookie last = set.getLast();
        xsrf = last.getMaxAge() == 0 || last.getValue().isEmpty() ? null : last.getValue();
      }
    }
  }

  // ---------------------------------------------------------------- fixtures

  @Autowired private WebApplicationContext context;
  @Autowired private JdbcClient jdbcClient;
  @Autowired private MutableClock clock;
  @Autowired private RecordingSender sender;
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
    clock.set(NOW);
    sender.sent.clear();

    identity = identityRepository.save(Identity.create(IdentityStatus.ACTIVE));
    credential = emailCredentialService.register(identity, ADDRESS);
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  private static MockHttpServletRequestBuilder generate(String body) {
    return post("/ott/generate").contentType(MediaType.APPLICATION_JSON).content(body);
  }

  private static MockHttpServletRequestBuilder confirm(String token) {
    return confirmBody("{\"token\":\"" + token + "\"}");
  }

  private static MockHttpServletRequestBuilder confirmBody(String body) {
    return post("/login/ott").contentType(MediaType.APPLICATION_JSON).content(body);
  }

  private String requestLink(Browser browser) throws Exception {
    int before = sender.sent.size();
    MvcResult result = browser.performWithCsrf(generate("{\"email\":\"" + ADDRESS + "\"}"));
    assertThat(result.getResponse().getStatus()).isEqualTo(202);
    assertThat(sender.sent).hasSize(before + 1);
    return sender.sent.getLast().token();
  }

  private Browser signedIn() throws Exception {
    Browser browser = new Browser(mvc);
    String token = requestLink(browser);
    assertThat(browser.performWithCsrf(confirm(token)).getResponse().getStatus()).isEqualTo(200);
    assertThat(browser.perform(get("/who/me")).getResponse().getStatus()).isEqualTo(200);
    return browser;
  }

  private static void assertInvalidToken(MvcResult result) throws Exception {
    assertThat(result.getResponse().getStatus()).isEqualTo(401);
    assertThat(result.getResponse().getContentAsString())
        .isEqualTo("{\"error\":\"invalid_token\"}");
  }

  // ---------------------------------------------------------------- the flow

  @Test
  void emailLinkSignInEndToEnd() throws Exception {
    Browser browser = new Browser(mvc);

    MvcResult generated = browser.performWithCsrf(generate("{\"email\":\"owner@example.com\"}"));
    assertThat(generated.getResponse().getStatus()).isEqualTo(202);
    assertThat(sender.sent).hasSize(1);
    SentLink link = sender.sent.getFirst();
    assertThat(link.address()).isEqualTo(ADDRESS);
    assertThat(link.link()).startsWith(LINK_BASE_URL + "#token=");

    String sessionIdBefore = browser.session.getId();
    String xsrfBefore = browser.xsrf;
    MvcResult signedIn = browser.performWithCsrf(confirm(link.token()));
    assertThat(signedIn.getResponse().getStatus()).isEqualTo(200);
    assertThat(signedIn.getResponse().getContentType())
        .startsWith(MediaType.APPLICATION_JSON_VALUE);
    assertThat(signedIn.getResponse().getContentAsString())
        .isEqualTo("{\"identityId\":\"" + identity.id() + "\"}");
    assertThat(browser.session.getId()).isNotEqualTo(sessionIdBefore);
    assertThat(browser.xsrf).isNotNull().isNotEqualTo(xsrfBefore);

    mvc.perform(get("/who/me").session(browser.session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.identityId").value(identity.id().toString()))
        .andExpect(jsonPath("$.permissions.length()").value(1))
        .andExpect(jsonPath("$.permissions[0]").value("task.read"));
  }

  @Test
  void unknownAddressGetsTheSame202AndNoEmail() throws Exception {
    Browser browser = new Browser(mvc);

    MvcResult result = browser.performWithCsrf(generate("{\"email\":\"stranger@example.com\"}"));

    assertThat(result.getResponse().getStatus()).isEqualTo(202);
    assertThat(sender.sent).isEmpty();
  }

  @Test
  void secondRequestWithinTheIntervalSendsNoEmail() throws Exception {
    Browser browser = new Browser(mvc);
    requestLink(browser);
    clock.advance(Duration.ofSeconds(30));

    MvcResult second = browser.performWithCsrf(generate("{\"email\":\"" + ADDRESS + "\"}"));

    assertThat(second.getResponse().getStatus()).isEqualTo(202);
    assertThat(sender.sent).hasSize(1);
  }

  @Test
  void newLinkReplacesTheOldOne() throws Exception {
    Browser browser = new Browser(mvc);
    String oldToken = requestLink(browser);
    clock.advance(Duration.ofSeconds(61));
    String newToken = requestLink(browser);

    assertInvalidToken(browser.performWithCsrf(confirm(oldToken)));
    assertThat(browser.performWithCsrf(confirm(newToken)).getResponse().getStatus()).isEqualTo(200);
  }

  @Test
  void usedLinkIsRejected() throws Exception {
    Browser browser = new Browser(mvc);
    String token = requestLink(browser);
    assertThat(browser.performWithCsrf(confirm(token)).getResponse().getStatus()).isEqualTo(200);

    assertInvalidToken(new Browser(mvc).performWithCsrf(confirm(token)));
  }

  @Test
  void expiredLinkIsRejected() throws Exception {
    Browser browser = new Browser(mvc);
    String token = requestLink(browser);
    clock.advance(Duration.ofMinutes(5).plusSeconds(1));

    assertInvalidToken(browser.performWithCsrf(confirm(token)));
  }

  @Test
  void suspendedIdentityCannotSignIn() throws Exception {
    Browser browser = new Browser(mvc);
    String token = requestLink(browser);
    identityRepository.save(identity.withStatus(IdentityStatus.SUSPENDED));

    assertInvalidToken(browser.performWithCsrf(confirm(token)));
    assertThat(browser.perform(get("/who/me")).getResponse().getStatus()).isEqualTo(401);

    clock.advance(Duration.ofSeconds(61));
    browser.performWithCsrf(generate("{\"email\":\"" + ADDRESS + "\"}"));
    assertThat(sender.sent).hasSize(1);
  }

  @Test
  void suspendingEndsAnExistingSession() throws Exception {
    Browser browser = signedIn();

    identityRepository.save(identity.withStatus(IdentityStatus.SUSPENDED));

    assertThat(browser.perform(get("/who/me")).getResponse().getStatus()).isEqualTo(401);
  }

  @Test
  void revokingTheCredentialEndsTheSession() throws Exception {
    Browser browser = signedIn();

    emailCredentialService.revoke(credential.id());

    assertThat(browser.perform(get("/who/me")).getResponse().getStatus()).isEqualTo(401);
  }

  @Test
  void signInWithoutCsrfIs403() throws Exception {
    Browser browser = new Browser(mvc);

    assertThat(
            browser.perform(generate("{\"email\":\"" + ADDRESS + "\"}")).getResponse().getStatus())
        .isEqualTo(403);
    assertThat(sender.sent).isEmpty();

    String token = requestLink(browser);
    assertThat(browser.perform(confirm(token)).getResponse().getStatus()).isEqualTo(403);
    assertThat(browser.perform(get("/who/me")).getResponse().getStatus()).isEqualTo(401);
  }

  @Test
  void addressIsMatchedAfterNormalizing() throws Exception {
    Browser browser = new Browser(mvc);

    MvcResult result = browser.performWithCsrf(generate("{\"email\":\"  Owner@Example.COM \"}"));

    assertThat(result.getResponse().getStatus()).isEqualTo(202);
    assertThat(sender.sent).hasSize(1);
    assertThat(sender.sent.getFirst().address()).isEqualTo(ADDRESS);
  }

  @Test
  void malformedGenerateRequestsAnswer202AndSendNothing() throws Exception {
    Browser browser = new Browser(mvc);

    for (String body : List.of("not json", "{}", "{\"email\":42}", "{\"email\":\"\"}", "[]", "")) {
      assertThat(browser.performWithCsrf(generate(body)).getResponse().getStatus())
          .as(body)
          .isEqualTo(202);
    }
    assertThat(sender.sent).isEmpty();
  }

  @Test
  void malformedConfirmIs401() throws Exception {
    Browser browser = new Browser(mvc);

    for (String body : List.of("not json", "{}", "{\"token\":42}", "{\"token\":\"\"}", "")) {
      assertInvalidToken(browser.performWithCsrf(confirmBody(body)));
    }
  }

  @Test
  void concurrentRequestsSendOneEmail() {
    new WebApplicationContextRunner()
        .withUserConfiguration(App.class)
        .withPropertyValues(
            "who.initialize-schema=always",
            "who.email-link.link-base-url=" + LINK_BASE_URL,
            "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
            "spring.datasource.username=" + POSTGRES.getUsername(),
            "spring.datasource.password=" + POSTGRES.getPassword())
        .run(
            other -> {
              assertThat(other).hasNotFailed();
              MockMvc otherMvc =
                  MockMvcBuilders.webAppContextSetup(other).apply(springSecurity()).build();
              RecordingSender otherSender = other.getBean(RecordingSender.class);
              Browser first = new Browser(mvc);
              Browser second = new Browser(otherMvc);
              first.perform(get("/who/me"));
              second.perform(get("/who/me"));

              CyclicBarrier barrier = new CyclicBarrier(2);
              ExecutorService pool = Executors.newFixedThreadPool(2);
              try {
                List<Future<Integer>> statuses =
                    pool.invokeAll(
                        List.of(
                            () -> {
                              barrier.await(10, TimeUnit.SECONDS);
                              return first
                                  .performWithCsrf(generate("{\"email\":\"" + ADDRESS + "\"}"))
                                  .getResponse()
                                  .getStatus();
                            },
                            () -> {
                              barrier.await(10, TimeUnit.SECONDS);
                              return second
                                  .performWithCsrf(generate("{\"email\":\"" + ADDRESS + "\"}"))
                                  .getResponse()
                                  .getStatus();
                            }));
                for (Future<Integer> s : statuses) {
                  assertThat(s.get()).isEqualTo(202);
                }
              } finally {
                pool.shutdownNow();
              }

              assertThat(sender.sent.size() + otherSender.sent.size()).isEqualTo(1);
            });
  }

  // ---------------------------------------------------------------- startup validation

  @Configuration(proxyBeanMethods = false)
  static class StartupConfig {
    @Bean
    DataSource dataSource() {
      return new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).build();
    }

    @Bean
    JdbcClient jdbcClient(DataSource dataSource) {
      return JdbcClient.create(dataSource);
    }

    @Bean
    EmailLinkSender emailLinkSender() {
      return (address, link) -> {};
    }
  }

  private final ApplicationContextRunner startupRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(WhoAutoConfiguration.class))
          .withUserConfiguration(StartupConfig.class);

  @Test
  void absentLinkBaseUrlMeansNoEmailLinkSignIn() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(WhoAutoConfiguration.class))
        .withUserConfiguration(NoSenderConfig.class)
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              assertThat(ctx).doesNotHaveBean(EmailLinkLoginMethod.class);
              assertThat(ctx).doesNotHaveBean(EmailLinkIssuer.class);
              assertThat(ctx).doesNotHaveBean("whoEmailLinkExecutor");
            });
  }

  @Test
  void linkBaseUrlWithoutASenderFailsStartup() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(WhoAutoConfiguration.class))
        .withUserConfiguration(NoSenderConfig.class)
        .withPropertyValues("who.email-link.link-base-url=" + LINK_BASE_URL)
        .run(
            ctx ->
                assertThat(ctx.getStartupFailure())
                    .isNotNull()
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("EmailLinkSender")
                    .hasMessageContaining("spring.mail.host")
                    .hasMessageContaining("who.email-link.from"));
  }

  @Test
  void blankLinkBaseUrlFailsStartup() {
    startupRunner
        .withPropertyValues("who.email-link.link-base-url=")
        .run(
            ctx ->
                assertThat(ctx.getStartupFailure())
                    .isNotNull()
                    .rootCause()
                    .hasMessageContaining("who.email-link.link-base-url"));
  }

  @Configuration(proxyBeanMethods = false)
  static class NoSenderConfig {
    @Bean
    DataSource dataSource() {
      return new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).build();
    }

    @Bean
    JdbcClient jdbcClient(DataSource dataSource) {
      return JdbcClient.create(dataSource);
    }
  }

  @Test
  void resendIntervalLongerThanTtlFailsStartup() {
    startupRunner
        .withPropertyValues(
            "who.email-link.link-base-url=" + LINK_BASE_URL,
            "who.email-link.token-ttl=1m",
            "who.email-link.resend-interval=2m")
        .run(
            ctx ->
                assertThat(ctx.getStartupFailure())
                    .isNotNull()
                    .rootCause()
                    .hasMessageContaining("who.email-link.resend-interval")
                    .hasMessageContaining("who.email-link.token-ttl"));
  }

  @Test
  void validSettingsStartWithTheDefaults() {
    startupRunner
        .withPropertyValues("who.email-link.link-base-url=" + LINK_BASE_URL)
        .run(ctx -> assertThat(ctx).hasNotFailed());
  }
}
