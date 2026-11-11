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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpSession;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.autoconfigure.EmailLinkSignInTest.MutableClock;
import org.jwcarman.who.autoconfigure.EmailLinkSignInTest.RecordingSender;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.IdentityStatus;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.core.spi.PermissionsResolver;
import org.jwcarman.who.emaillink.EmailCredential;
import org.jwcarman.who.emaillink.EmailCredentialService;
import org.jwcarman.who.passkey.PasskeyAddedNotifier;
import org.jwcarman.who.passkey.PasskeyService;
import org.jwcarman.who.passkey.PasskeySummary;
import org.jwcarman.who.spring.security.WhoWebSecurity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The whole passkey flow, through who's autoconfiguration, a browser chain that applies {@code
 * WhoWebSecurity.passwordless()}, Spring's own WebAuthn endpoints, an emulated authenticator and
 * PostgreSQL. Email links are on too: they are how a user first signs in to register a passkey.
 */
@SpringBootTest(
    classes = PasskeySignInTest.App.class,
    properties = {
      "who.initialize-schema=always",
      "who.email-link.link-base-url=https://app.example.com/signin",
      "who.passkey.rp-id=app.example.com"
    })
@Testcontainers
class PasskeySignInTest {

  static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");
  static final String ORIGIN = "https://app.example.com";
  static final String OWNER = "owner@example.com";
  static final String OTHER = "other@example.com";
  static final String XSRF_COOKIE = "XSRF-TOKEN";
  static final String XSRF_HEADER = "X-XSRF-TOKEN";

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
      return new MutableClock(NOW);
    }

    @Bean
    RecordingSender recordingSender() {
      return new RecordingSender();
    }

    @Bean
    RecordingNotifier recordingNotifier() {
      return new RecordingNotifier();
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

  record AddedPasskey(UUID identityId, String label) {}

  static final class RecordingNotifier implements PasskeyAddedNotifier {

    final List<AddedPasskey> added = new CopyOnWriteArrayList<>();

    @Override
    public void passkeyAdded(UUID identityId, String label) {
      added.add(new AddedPasskey(identityId, label));
    }
  }

  /**
   * What a browser keeps between requests: its session (a new one after the old is invalidated) and
   * the script-readable CSRF cookie.
   */
  static final class Browser {

    private final MockMvc mvc;
    MockHttpSession session = new MockHttpSession();
    String xsrf;

    Browser(MockMvc mvc) {
      this.mvc = mvc;
    }

    MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
      if (session.isInvalid()) {
        session = new MockHttpSession();
      }
      MockHttpSession current = session;
      // as a servlet container does when the browser presents its session cookie
      request
          .session(current)
          .with(
              r -> {
                r.setRequestedSessionId(current.getId());
                r.setRequestedSessionIdValid(true);
                return r;
              });
      if (xsrf != null) {
        request.cookie(new Cookie(XSRF_COOKIE, xsrf));
      }
      MvcResult result = mvc.perform(request).andReturn();
      HttpSession after = result.getRequest().getSession(false);
      if (after instanceof MockHttpSession mock && !mock.isInvalid()) {
        session = mock;
      }
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

    int me() throws Exception {
      return perform(get("/who/me")).getResponse().getStatus();
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
  @Autowired private RecordingNotifier notifier;
  @Autowired private IdentityRepository identityRepository;
  @Autowired private EmailCredentialService emailCredentialService;
  @Autowired private PasskeyService passkeyService;

  private MockMvc mvc;
  private Identity owner;
  private EmailCredential ownerEmail;
  private Identity other;

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
    notifier.added.clear();

    owner = identityRepository.save(Identity.create(IdentityStatus.ACTIVE));
    ownerEmail = emailCredentialService.register(owner, OWNER);
    other = identityRepository.save(Identity.create(IdentityStatus.ACTIVE));
    emailCredentialService.register(other, OTHER);
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request) {
    return request.contentType(MediaType.APPLICATION_JSON);
  }

  private Browser signedInByEmail(String address) throws Exception {
    Browser browser = new Browser(mvc);
    signInByEmail(browser, address);
    return browser;
  }

  /** Signs the browser in by email link, on whatever session it already has. */
  private void signInByEmail(Browser browser, String address) throws Exception {
    int before = sender.sent.size();
    MvcResult generated =
        browser.performWithCsrf(
            json(post("/ott/generate")).content("{\"email\":\"" + address + "\"}"));
    assertThat(generated.getResponse().getStatus()).isEqualTo(202);
    assertThat(sender.sent).hasSize(before + 1);
    String token = sender.sent.getLast().token();
    MvcResult confirmed =
        browser.performWithCsrf(json(post("/login/ott")).content("{\"token\":\"" + token + "\"}"));
    assertThat(confirmed.getResponse().getStatus()).isEqualTo(200);
    assertThat(browser.me()).isEqualTo(200);
  }

  private static MvcResult creationOptions(Browser browser) throws Exception {
    return browser.performWithCsrf(post("/webauthn/register/options"));
  }

  private static String creationOptionsJson(Browser browser) throws Exception {
    MvcResult options = creationOptions(browser);
    assertThat(options.getResponse().getStatus()).isEqualTo(200);
    return options.getResponse().getContentAsString();
  }

  private static MvcResult register(Browser browser, String body) throws Exception {
    return browser.performWithCsrf(json(post("/webauthn/register")).content(body));
  }

  /** Registers a passkey on the device from the signed-in browser. */
  private static void registerPasskey(Browser browser, PasskeyDevice device, String label)
      throws Exception {
    MvcResult registered = register(browser, device.register(creationOptionsJson(browser), label));
    assertThat(registered.getResponse().getStatus()).isEqualTo(200);
    assertThat(registered.getResponse().getContentAsString()).isEqualTo("{\"success\":true}");
  }

  private static String requestOptionsJson(Browser browser) throws Exception {
    MvcResult options = browser.performWithCsrf(post("/webauthn/authenticate/options"));
    assertThat(options.getResponse().getStatus()).isEqualTo(200);
    return options.getResponse().getContentAsString();
  }

  private static MvcResult signInWithPasskey(Browser browser, String assertion) throws Exception {
    return browser.performWithCsrf(json(post("/login/webauthn")).content(assertion));
  }

  private static MvcResult signInWithPasskey(Browser browser, PasskeyDevice device)
      throws Exception {
    return signInWithPasskey(browser, device.signIn(requestOptionsJson(browser)));
  }

  private static void signOut(Browser browser) throws Exception {
    assertThat(browser.performWithCsrf(post("/logout")).getResponse().getStatus()).isEqualTo(204);
    assertThat(browser.me()).isEqualTo(401);
  }

  /** A passkey registered for the owner, from an email-link session that then signs out. */
  private PasskeyDevice ownerWithPasskey() throws Exception {
    Browser browser = signedInByEmail(OWNER);
    PasskeyDevice device = new PasskeyDevice(ORIGIN);
    registerPasskey(browser, device, "Laptop");
    signOut(browser);
    return device;
  }

  private UUID credentialUuid(PasskeyDevice device) {
    return jdbcClient
        .sql("SELECT credential_uuid FROM who_passkey_credential WHERE credential_id = :id")
        .param("id", device.credentialId())
        .query(UUID.class)
        .single();
  }

  private static void assertSignedInAs(MvcResult result, Identity identity) throws Exception {
    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    assertThat(result.getResponse().getContentAsString())
        .isEqualTo("{\"identityId\":\"" + identity.id() + "\"}");
  }

  private static void assertInvalidCredential(MvcResult result) throws Exception {
    assertThat(result.getResponse().getStatus()).isEqualTo(401);
    // who writes no error body: with no advice, the status is all there is
    assertThat(result.getResponse().getContentAsString()).isEmpty();
  }

  private static String identityIdOf(Browser browser) throws Exception {
    MvcResult me = browser.perform(get("/who/me"));
    assertThat(me.getResponse().getStatus()).isEqualTo(200);
    String body = me.getResponse().getContentAsString();
    int start = body.indexOf("\"identityId\":\"") + "\"identityId\":\"".length();
    return body.substring(start, body.indexOf('"', start));
  }

  // ---------------------------------------------------------------- the flow

  @Test
  void registerThenSignInEndToEnd() throws Exception {
    Browser browser = signedInByEmail(OWNER);
    PasskeyDevice device = new PasskeyDevice(ORIGIN);

    registerPasskey(browser, device, "Laptop");

    // Spring's filters stored it through who's repository, linked to the identity
    UUID credentialUuid = credentialUuid(device);
    assertThat(
            jdbcClient
                .sql("SELECT label FROM who_passkey_credential WHERE credential_id = :id")
                .param("id", device.credentialId())
                .query(String.class)
                .single())
        .isEqualTo("Laptop");
    assertThat(
            jdbcClient
                .sql("SELECT identity_id FROM who_credential_identity WHERE credential_id = :id")
                .param("id", credentialUuid)
                .query(UUID.class)
                .single())
        .isEqualTo(owner.id());

    signOut(browser);

    String requestOptions = requestOptionsJson(browser);
    String sessionIdBefore = browser.session.getId();
    String xsrfBefore = browser.xsrf;
    MvcResult signedIn = signInWithPasskey(browser, device.signIn(requestOptions));

    assertSignedInAs(signedIn, owner);
    assertThat(signedIn.getResponse().getContentType())
        .startsWith(MediaType.APPLICATION_JSON_VALUE);
    assertThat(browser.session.getId()).isNotEqualTo(sessionIdBefore);
    assertThat(browser.xsrf).isNotNull().isNotEqualTo(xsrfBefore);
    assertThat(identityIdOf(browser)).isEqualTo(owner.id().toString());
  }

  @Test
  void registeringWhileSignedOutIs401() throws Exception {
    Browser browser = new Browser(mvc);

    MvcResult options = creationOptions(browser);
    assertThat(options.getResponse().getStatus()).isEqualTo(401);

    String body =
        new PasskeyDevice(ORIGIN).register(creationOptionsJson(signedInByEmail(OWNER)), "x");
    assertThat(register(browser, body).getResponse().getStatus()).isEqualTo(401);
    assertThat(
            jdbcClient
                .sql("SELECT count(*) FROM who_passkey_credential")
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  void registeringOutsideTheWindowIsReauthenticationRequired() throws Exception {
    Browser browser = signedInByEmail(OWNER);
    String options = creationOptionsJson(browser);
    PasskeyDevice device = new PasskeyDevice(ORIGIN);
    clock.advance(Duration.ofMinutes(10).plusSeconds(1));

    MvcResult registered = register(browser, device.register(options, "Laptop"));
    assertThat(registered.getResponse().getStatus()).isEqualTo(401);
    assertThat(registered.getResponse().getContentAsString()).isEmpty();

    MvcResult again = creationOptions(browser);
    assertThat(again.getResponse().getStatus()).isEqualTo(401);
    assertThat(again.getResponse().getContentAsString()).isEmpty();
    assertThat(
            jdbcClient
                .sql("SELECT count(*) FROM who_passkey_credential")
                .query(Long.class)
                .single())
        .isZero();
    // still signed in: only registering needs a recent sign-in
    assertThat(browser.me()).isEqualTo(200);
  }

  @Test
  void invalidRegistrationIs400() throws Exception {
    Browser browser = signedInByEmail(OWNER);
    // a device on another origin: verification fails
    PasskeyDevice phishing = new PasskeyDevice("https://evil.example.com");

    MvcResult registered = register(browser, phishing.register(creationOptionsJson(browser), "x"));

    assertThat(registered.getResponse().getStatus()).isEqualTo(400);
    assertThat(registered.getResponse().getContentAsString()).isEmpty();
    assertThat(
            jdbcClient
                .sql("SELECT count(*) FROM who_passkey_credential")
                .query(Long.class)
                .single())
        .isZero();
    assertThat(notifier.added).isEmpty();
  }

  @Test
  void staleOptionsCannotRegisterAPasskeyForAnotherIdentity() throws Exception {
    Browser browser = signedInByEmail(OWNER);
    String ownersOptions = creationOptionsJson(browser);
    // someone else signs in on the same browser session; the owner's options stay in it
    signInByEmail(browser, OTHER);
    assertThat(identityIdOf(browser)).isEqualTo(other.id().toString());

    MvcResult registered =
        register(browser, new PasskeyDevice(ORIGIN).register(ownersOptions, "Planted"));

    assertThat(registered.getResponse().getStatus()).isEqualTo(400);
    assertThat(registered.getResponse().getContentAsString()).isEmpty();
    assertThat(
            jdbcClient
                .sql("SELECT count(*) FROM who_passkey_credential")
                .query(Long.class)
                .single())
        .isZero();
    assertThat(passkeyService.list(owner.id())).isEmpty();
    assertThat(passkeyService.list(other.id())).isEmpty();
    assertThat(notifier.added).isEmpty();
  }

  @Test
  void passkeySignInReplacesAnEmailLinkSession() throws Exception {
    Browser browser = signedInByEmail(OWNER);
    PasskeyDevice device = new PasskeyDevice(ORIGIN);
    registerPasskey(browser, device, "Laptop");
    String sessionIdBefore = browser.session.getId();

    MvcResult signedIn = signInWithPasskey(browser, device);

    assertSignedInAs(signedIn, owner);
    assertThat(browser.session.getId()).isNotEqualTo(sessionIdBefore);
    // the session now rests on the passkey, not the email credential
    emailCredentialService.revoke(ownerEmail.id());
    assertThat(browser.me()).isEqualTo(200);
    passkeyService.revoke(credentialUuid(device));
    assertThat(browser.me()).isEqualTo(401);
  }

  @Test
  void revokedPasskeyEndsItsSession() throws Exception {
    Browser emailSession = signedInByEmail(OWNER);
    PasskeyDevice laptop = new PasskeyDevice(ORIGIN);
    PasskeyDevice phone = new PasskeyDevice(ORIGIN);
    registerPasskey(emailSession, laptop, "Laptop");
    registerPasskey(emailSession, phone, "Phone");

    // through PasskeyService.revoke
    Browser laptopSession = new Browser(mvc);
    assertSignedInAs(signInWithPasskey(laptopSession, laptop), owner);
    passkeyService.revoke(credentialUuid(laptop));
    assertThat(laptopSession.me()).isEqualTo(401);
    assertInvalidCredential(signInWithPasskey(new Browser(mvc), laptop));

    // through Spring's DELETE /webauthn/register/{id}, from the passkey's own session
    Browser phoneSession = new Browser(mvc);
    assertSignedInAs(signInWithPasskey(phoneSession, phone), owner);
    MvcResult deleted =
        phoneSession.performWithCsrf(delete("/webauthn/register/" + phone.credentialId()));
    assertThat(deleted.getResponse().getStatus()).isEqualTo(204);
    assertThat(phoneSession.me()).isEqualTo(401);
    assertThat(
            jdbcClient
                .sql("SELECT count(*) FROM who_passkey_credential")
                .query(Long.class)
                .single())
        .isZero();
    assertThat(
            jdbcClient
                .sql("SELECT count(*) FROM who_credential_identity WHERE identity_id = :id")
                .param("id", owner.id())
                .query(Long.class)
                .single())
        .isEqualTo(1L); // only the email credential
    assertInvalidCredential(signInWithPasskey(new Browser(mvc), phone));
    // the email-link session is unaffected
    assertThat(emailSession.me()).isEqualTo(200);
  }

  @Test
  void listedCredentialIdDrivesSpringsDelete() throws Exception {
    Browser browser = signedInByEmail(OWNER);
    PasskeyDevice device = new PasskeyDevice(ORIGIN);
    registerPasskey(browser, device, "Laptop");
    PasskeySummary listed = passkeyService.list(owner.id()).getFirst();
    assertThat(listed.credentialId()).isEqualTo(device.credentialId());

    MvcResult deleted =
        browser.performWithCsrf(delete("/webauthn/register/" + listed.credentialId()));

    assertThat(deleted.getResponse().getStatus()).isEqualTo(204);
    assertThat(passkeyService.list(owner.id())).isEmpty();
    assertInvalidCredential(signInWithPasskey(new Browser(mvc), device));
  }

  @Test
  void cannotDeleteAnotherIdentitysPasskey() throws Exception {
    PasskeyDevice ownerDevice = ownerWithPasskey();
    Browser intruder = signedInByEmail(OTHER);
    // with and without a passkey user entity of its own
    MvcResult refused =
        intruder.performWithCsrf(delete("/webauthn/register/" + ownerDevice.credentialId()));
    assertThat(refused.getResponse().getStatus()).isEqualTo(403);
    registerPasskey(intruder, new PasskeyDevice(ORIGIN), "Intruder");

    MvcResult refusedAgain =
        intruder.performWithCsrf(delete("/webauthn/register/" + ownerDevice.credentialId()));

    assertThat(refusedAgain.getResponse().getStatus()).isEqualTo(403);
    assertSignedInAs(signInWithPasskey(new Browser(mvc), ownerDevice), owner);
  }

  @Test
  void suspendedIdentityCannotSignInWithAPasskey() throws Exception {
    PasskeyDevice device = ownerWithPasskey();
    identityRepository.save(owner.withStatus(IdentityStatus.SUSPENDED));
    Browser browser = new Browser(mvc);

    assertInvalidCredential(signInWithPasskey(browser, device));
    assertThat(browser.me()).isEqualTo(401);
  }

  @Test
  void unknownCredentialIs401InvalidCredential() throws Exception {
    // the device creates a credential for real options, but it is never registered
    PasskeyDevice device = new PasskeyDevice(ORIGIN);
    device.register(creationOptionsJson(signedInByEmail(OWNER)), "never sent");
    Browser browser = new Browser(mvc);

    assertInvalidCredential(signInWithPasskey(browser, device));
    assertThat(browser.me()).isEqualTo(401);
  }

  @Test
  void assertionUserHandleCannotChooseTheIdentity() throws Exception {
    PasskeyDevice ownerDevice = ownerWithPasskey();
    Browser otherBrowser = signedInByEmail(OTHER);
    String otherOptions = creationOptionsJson(otherBrowser);
    byte[] otherHandle = new PasskeyDevice(ORIGIN).userHandle(otherOptions);
    registerPasskey(otherBrowser, new PasskeyDevice(ORIGIN), "Other");
    // the forged handle really is the other identity's, as who stored it
    String otherStoredHandle =
        jdbcClient
            .sql("SELECT id FROM who_passkey_user WHERE identity_id = :id")
            .param("id", other.id())
            .query(String.class)
            .single();
    assertThat(Base64.getUrlEncoder().withoutPadding().encodeToString(otherHandle))
        .isEqualTo(otherStoredHandle);
    Browser browser = new Browser(mvc);

    MvcResult result =
        signInWithPasskey(browser, ownerDevice.signIn(requestOptionsJson(browser), otherHandle));

    // the identity follows the verified credential, never the claimed user handle
    assertSignedInAs(result, owner);
    assertThat(identityIdOf(browser)).isEqualTo(owner.id().toString());
  }

  @Test
  void pickerNameIsTheEmail() throws Exception {
    String options = creationOptionsJson(signedInByEmail(OWNER));

    JsonNode user = new JsonMapper().readTree(options).path("user");
    assertThat(user.path("name").asString()).isEqualTo(OWNER);
    assertThat(user.path("displayName").asString()).isEqualTo(OWNER);
    assertThat(
            jdbcClient
                .sql("SELECT name FROM who_passkey_user WHERE identity_id = :id")
                .param("id", owner.id())
                .query(String.class)
                .single())
        .isEqualTo(OWNER);
  }

  @Test
  void addedPasskeyNotifiesTheOwner() throws Exception {
    ownerWithPasskey();

    assertThat(notifier.added).containsExactly(new AddedPasskey(owner.id(), "Laptop"));
  }
}
