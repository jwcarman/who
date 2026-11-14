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
package org.jwcarman.who.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.event.CredentialAdded;
import org.jwcarman.who.core.event.CredentialKind;
import org.jwcarman.who.core.event.CredentialRevoked;
import org.jwcarman.who.core.event.SignInFailed;
import org.jwcarman.who.core.event.SignInFailureReason;
import org.jwcarman.who.core.event.SignInMethod;
import org.jwcarman.who.core.event.SignInSucceeded;
import org.jwcarman.who.core.event.WhoEvent;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.core.service.WhoService;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.authentication.AuthenticationEventPublisher;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DefaultAuthenticationEventPublisher;
import org.springframework.security.authentication.event.AbstractAuthenticationEvent;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;

/**
 * The events API keys publish: a sign-in on every request that carries a key, through a chain that
 * adds the filter with {@link WhoApiKeySecurity#apiKey()}, and a credential event when {@link
 * ApiKeyService} creates or revokes a key.
 */
@Import(ApiKeyEventsTest.Observed.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ApiKeyEventsTest extends AbstractApiKeySignInTest {

  /** Successful authentications the application heard about. */
  static final List<AuthenticationSuccessEvent> SUCCESSES = new CopyOnWriteArrayList<>();

  /** Failed authentications the application heard about. */
  static final List<AbstractAuthenticationFailureEvent> FAILURES = new CopyOnWriteArrayList<>();

  /** Names of the observations that completed, as an application with Actuator would record. */
  static final List<String> OBSERVED = new CopyOnWriteArrayList<>();

  /** What an application that observes authentications and listens for their events has. */
  @TestConfiguration
  static class Observed {

    @Bean
    @Primary
    AuthenticationEventPublisher authenticationEventPublisher() {
      return new DefaultAuthenticationEventPublisher();
    }

    @Bean
    ObservationRegistry observationRegistry() {
      ObservationRegistry registry = ObservationRegistry.create();
      registry
          .observationConfig()
          .observationHandler(
              new ObservationHandler<>() {
                @Override
                public boolean supportsContext(Observation.Context context) {
                  return true;
                }

                @Override
                public void onStop(Observation.Context context) {
                  OBSERVED.add(context.getName());
                }
              });
      return registry;
    }

    @Bean
    ApplicationListener<AuthenticationSuccessEvent> successListener() {
      return SUCCESSES::add;
    }

    @Bean
    ApplicationListener<AbstractAuthenticationFailureEvent> failureListener() {
      return FAILURES::add;
    }
  }

  @Autowired private ApiKeyService apiKeyService;
  @Autowired private ApiKeyCredentialRepository apiKeyCredentialRepository;
  @Autowired private CredentialIdentityRepository credentialIdentityRepository;
  @Autowired private IdentityRepository identityRepository;

  private Identity identity;

  @BeforeEach
  void setUp() {
    SUCCESSES.clear();
    FAILURES.clear();
    OBSERVED.clear();
    identity = identityRepository.save(Identity.create());
  }

  private UUID credentialIdOf(String key) {
    return apiKeyCredentialRepository
        .findByKeyHash(ApiKeyService.sha256Hex(key))
        .orElseThrow()
        .id();
  }

  private int statusOf(String key) throws Exception {
    return mvc.perform(get("/open/whoami").header(HEADER, key))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  @Nested
  class Signing_in {

    @Test
    void each_request_publishes_sign_in_succeeded_with_api_key() throws Exception {
      String key = apiKeyService.create(identity, "events");
      SignInSucceeded expected =
          new SignInSucceeded(NOW, SignInMethod.API_KEY, identity.id(), credentialIdOf(key));

      assertThat(statusOf(key)).isEqualTo(200);
      assertThat(statusOf(key)).isEqualTo(200);

      // one per request, and nothing else in the chain publishes a second one
      assertThat(publishedEvents()).containsExactly(expected, expected);
    }

    @Test
    void an_unknown_key_publishes_sign_in_failed_with_unknown_key() throws Exception {
      assertThat(statusOf("who_unknown")).isEqualTo(401);

      assertThat(publishedEvents())
          .containsExactly(
              new SignInFailed(
                  NOW, SignInMethod.API_KEY, SignInFailureReason.UNKNOWN_KEY, null, null, null));
    }

    @Test
    void a_revoked_key_publishes_sign_in_failed_with_unknown_key() throws Exception {
      String key = apiKeyService.create(identity, "events");
      UUID credentialId = credentialIdOf(key);
      apiKeyService.revoke(credentialId);

      assertThat(statusOf(key)).isEqualTo(401);

      assertThat(publishedEvents())
          .singleElement()
          .isInstanceOfSatisfying(
              SignInFailed.class,
              failed -> assertThat(failed.reason()).isEqualTo(SignInFailureReason.UNKNOWN_KEY));
    }

    @Test
    void a_key_whose_link_is_gone_publishes_sign_in_failed_with_unknown_key_and_its_id()
        throws Exception {
      String key = apiKeyService.create(identity, "events");
      UUID credentialId = credentialIdOf(key);
      credentialIdentityRepository.unlink(credentialId);

      assertThat(statusOf(key)).isEqualTo(401);

      assertThat(publishedEvents())
          .containsExactly(
              new SignInFailed(
                  NOW,
                  SignInMethod.API_KEY,
                  SignInFailureReason.UNKNOWN_KEY,
                  null,
                  null,
                  credentialId));
    }

    @Test
    void the_sign_in_is_observed_when_the_application_observes() throws Exception {
      String key = apiKeyService.create(identity, "events");

      assertThat(statusOf(key)).isEqualTo(200);

      assertThat(OBSERVED)
          // the chain itself is observed, so the registry is in use
          .contains("spring.security.filterchains")
          .contains("spring.security.authentications");
    }

    @Test
    void the_application_hears_spring_authentication_events() throws Exception {
      String key = apiKeyService.create(identity, "events");

      statusOf(key);
      statusOf("who_unknown");

      assertThat(SUCCESSES).hasSize(1);
      assertThat(FAILURES).hasSize(1);
    }
  }

  @Nested
  class Resolving_a_key {

    @Test
    void a_missing_resolution_is_treated_as_not_linked() {
      ApiKeyCredentialRepository repository = mock(ApiKeyCredentialRepository.class);
      ApiKeyCredential credential = new ApiKeyCredential(UUID.randomUUID(), "events", "hash");
      when(repository.findByKeyHash(ApiKeyService.sha256Hex("who_key")))
          .thenReturn(Optional.of(credential));
      List<WhoEvent> published = new ArrayList<>();
      // the mocked WhoService stubs nothing, so resolveDetailed answers null
      ApiKeyAuthenticationProvider provider =
          new ApiKeyAuthenticationProvider(
              repository, mock(WhoService.class), published::add, Clock.fixed(NOW, ZoneOffset.UTC));
      ApiKeyAuthenticationToken token = new ApiKeyAuthenticationToken("who_key");

      assertThatThrownBy(() -> provider.authenticate(token))
          .isInstanceOf(BadCredentialsException.class);

      assertThat(published)
          .containsExactly(
              new SignInFailed(
                  NOW,
                  SignInMethod.API_KEY,
                  SignInFailureReason.UNKNOWN_KEY,
                  null,
                  null,
                  credential.id()));
    }
  }

  @Nested
  class Keeping_the_key_secret {

    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    private final List<Logger> loggers =
        List.of(
            root,
            (Logger) LoggerFactory.getLogger("org.jwcarman.who"),
            (Logger) LoggerFactory.getLogger("org.springframework.security"));
    private final List<Level> previousLevels = new ArrayList<>();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void captureEveryLogLine() {
      for (Logger logger : loggers) {
        previousLevels.add(logger.getLevel());
        logger.setLevel(Level.TRACE);
      }
      logs.start();
      root.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
      root.detachAppender(logs);
      for (int i = 0; i < loggers.size(); i++) {
        loggers.get(i).setLevel(previousLevels.get(i));
      }
    }

    @Test
    void the_raw_key_appears_in_no_event_and_no_log_line() throws Exception {
      String key = apiKeyService.create(identity, "events");
      String unknown = "who_" + "u".repeat(43);
      List<String> keys = List.of(key, unknown);
      List<String> hashes = List.of(ApiKeyService.sha256Hex(key), ApiKeyService.sha256Hex(unknown));
      logs.list.clear();

      assertThat(statusOf(key)).isEqualTo(200);
      assertThat(statusOf(unknown)).isEqualTo(401);

      // every line, from every logger at every level
      List<String> logged = new ArrayList<>();
      // who's own lines; the JDBC driver traces the hash it is asked to look up
      List<String> loggedByWho = new ArrayList<>();
      for (ILoggingEvent line : logs.list) {
        List<String> texts = new ArrayList<>();
        texts.add(line.getFormattedMessage());
        if (line.getThrowableProxy() != null) {
          texts.add(line.getThrowableProxy().getMessage());
        }
        logged.addAll(texts);
        if (line.getLoggerName().startsWith("org.jwcarman.who")) {
          loggedByWho.addAll(texts);
        }
      }
      // the failure was logged by who, so the capture works
      assertThat(loggedByWho).anyMatch(line -> line.contains("UNKNOWN_KEY"));

      List<String> published = new ArrayList<>();
      EVENTS.forEach(event -> published.add(event.toString()));
      HANDED_OFF.forEach(exception -> published.add(exception.toString()));
      List<AbstractAuthenticationEvent> springEvents = new ArrayList<>(SUCCESSES);
      springEvents.addAll(FAILURES);
      for (AbstractAuthenticationEvent event : springEvents) {
        published.add(event.toString());
        published.add(event.getAuthentication().toString());
        assertThat(event.getAuthentication().getCredentials()).isNull();
      }
      for (AbstractAuthenticationFailureEvent failure : FAILURES) {
        published.add(failure.getException().toString());
        assertThat(failure.getException().getAuthenticationRequest().getCredentials()).isNull();
      }
      assertThat(publishedEvents()).hasSize(2);
      assertThat(springEvents).hasSize(2);

      for (String secret : keys) {
        assertThat(logged).noneMatch(line -> line != null && line.contains(secret));
        assertThat(published).noneMatch(text -> text.contains(secret));
      }
      for (String hash : hashes) {
        assertThat(loggedByWho).noneMatch(line -> line != null && line.contains(hash));
        assertThat(published).noneMatch(text -> text.contains(hash));
      }
    }
  }

  @Nested
  class Managing_keys {

    private final List<WhoEvent> events = new CopyOnWriteArrayList<>();
    private ApiKeyService service;

    @BeforeEach
    void setUpService() {
      service =
          new ApiKeyService(
              apiKeyCredentialRepository,
              credentialIdentityRepository,
              events::add,
              Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void create_and_revoke_publish_credential_events() {
      String key = service.create(identity, "CI pipeline");
      UUID credentialId = credentialIdOf(key);

      service.revoke(credentialId);

      assertThat(events)
          .containsExactly(
              new CredentialAdded(
                  NOW, identity.id(), credentialId, CredentialKind.API_KEY, "CI pipeline"),
              new CredentialRevoked(
                  NOW, identity.id(), credentialId, CredentialKind.API_KEY, "CI pipeline"))
          .noneMatch(event -> event.toString().contains(key));
    }

    @Test
    void revoking_an_unknown_key_publishes_nothing() {
      service.revoke(UUID.randomUUID());

      assertThat(events).isEmpty();
    }
  }
}
