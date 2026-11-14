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
package org.jwcarman.who.emaillink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.jwcarman.who.core.crypto.MessageDigests;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.event.WhoEvent;
import org.jwcarman.who.core.event.WhoEventPublisher;
import org.jwcarman.who.core.service.Resolution.Inactive;
import org.jwcarman.who.core.service.Resolution.NoIdentity;
import org.jwcarman.who.core.service.Resolution.Resolved;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.core.spi.Credential;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;

class EmailLinkIssuerTest {

  private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");
  private static final Duration TTL = Duration.ofMinutes(15);
  private static final Duration RESEND = Duration.ofSeconds(60);
  private static final String ADDRESS = "owner@example.com";
  private static final String TOKEN = "token-value";
  private static final int CODE_LENGTH = 6;
  private static final String SECRET = "0123456789abcdef0123456789abcdef";

  private final EmailCredentialService credentials = mock(EmailCredentialService.class);
  private final WhoService whoService = mock(WhoService.class);
  private final EmailLinkTokenRepository repository = mock(EmailLinkTokenRepository.class);
  private final EmailLinkSender sender = mock(EmailLinkSender.class);
  private final EmailLinkIssuer issuer =
      new EmailLinkIssuer(
          credentials,
          whoService,
          repository,
          sender,
          new EmailLinkSettings("https://app.test/signin", TTL, RESEND, CODE_LENGTH, 5, SECRET),
          Clock.fixed(NOW, ZoneOffset.UTC));

  private final EmailCredential credential = new EmailCredential(UUID.randomUUID(), ADDRESS);

  private final Logger logger = (Logger) LoggerFactory.getLogger(EmailLinkIssuer.class);
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

  @BeforeEach
  void captureLogs() {
    logs.start();
    logger.addAppender(logs);
  }

  @AfterEach
  void releaseLogs() {
    logger.detachAppender(logs);
  }

  private void assertOneSafeWarning(String exceptionClass, boolean withCredentialId) {
    assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.WARN).hasSize(1);
    ILoggingEvent warning =
        logs.list.stream().filter(e -> e.getLevel() == Level.WARN).findFirst().orElseThrow();
    String message = warning.getFormattedMessage();
    assertThat(message).contains(exceptionClass);
    if (withCredentialId) {
      assertThat(message).contains(credential.id().toString());
    }
    assertThat(warning.getThrowableProxy()).isNull();
    for (ILoggingEvent event : logs.list) {
      assertThat(event.getFormattedMessage())
          .doesNotContain(ADDRESS)
          .doesNotContain(TOKEN)
          .doesNotContain("secret-detail");
    }
  }

  private void known(boolean active) {
    when(credentials.findByAddress(ADDRESS)).thenReturn(Optional.of(credential));
    when(whoService.resolveDetailed(any(Credential.class)))
        .thenReturn(
            active
                ? new Resolved(new WhoPrincipal(Identity.create(), Set.of()))
                : new Inactive(UUID.randomUUID()));
  }

  @Test
  void knownActiveAddressIsSentAFragmentLink() {
    known(true);
    when(repository.issue(any(), anyString(), anyString(), any(), any(), any())).thenReturn(true);

    issuer.issue(ADDRESS, TOKEN);

    ArgumentCaptor<EmailLinkMessage> message = ArgumentCaptor.forClass(EmailLinkMessage.class);
    verify(sender).send(message.capture());
    String code = message.getValue().code();
    assertThat(message.getValue().address()).isEqualTo(ADDRESS);
    assertThat(message.getValue().link()).isEqualTo("https://app.test/signin#token=" + TOKEN);
    assertThat(message.getValue().expiresAt()).isEqualTo(NOW.plus(TTL));
    assertThat(code).hasSize(CODE_LENGTH).matches("[0-9A-HJKMNP-TV-Z]+");
    verify(repository)
        .issue(
            credential.id(),
            MessageDigests.sha256Hex(TOKEN),
            new EmailCodeHasher(SECRET).hash(credential.id(), code),
            NOW,
            NOW.plus(TTL),
            RESEND);
  }

  @Test
  void unknownAddressSendsNothing() {
    when(credentials.findByAddress(ADDRESS)).thenReturn(Optional.empty());

    issuer.issue(ADDRESS, TOKEN);

    verifyNoInteractions(repository, sender, whoService);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   ", JsonGenerateOneTimeTokenRequestResolver.NO_ADDRESS})
  void noAddressStopsBeforeTheLookup(String address) {
    issuer.issue(address, TOKEN);

    verifyNoInteractions(credentials, repository, sender, whoService);
  }

  @Test
  void inactiveIdentitySendsNothing() {
    known(false);

    issuer.issue(ADDRESS, TOKEN);

    verifyNoInteractions(repository, sender);
  }

  @Test
  void withinResendIntervalSendsNothing() {
    known(true);
    when(repository.issue(any(), anyString(), anyString(), any(), any(), any())).thenReturn(false);

    issuer.issue(ADDRESS, TOKEN);

    verifyNoInteractions(sender);
    verify(repository, never()).delete(any(), anyString());
  }

  @Test
  void failedSendRemovesTheTokenSoTheUserCanRetry() {
    known(true);
    when(repository.issue(any(), anyString(), anyString(), any(), any(), any())).thenReturn(true);
    doThrow(new IllegalStateException("smtp down")).when(sender).send(any(EmailLinkMessage.class));

    issuer.issue(ADDRESS, TOKEN);

    ArgumentCaptor<String> issuedHash = ArgumentCaptor.forClass(String.class);
    verify(repository).issue(any(), issuedHash.capture(), anyString(), any(), any(), any());
    verify(repository).delete(credential.id(), issuedHash.getValue());
  }

  @Test
  void failedSendIsLoggedWithoutTheAddressOrToken() {
    known(true);
    when(repository.issue(any(), anyString(), anyString(), any(), any(), any())).thenReturn(true);
    doThrow(new IllegalStateException("secret-detail " + ADDRESS))
        .when(sender)
        .send(any(EmailLinkMessage.class));

    issuer.issue(ADDRESS, TOKEN);

    assertOneSafeWarning(IllegalStateException.class.getName(), true);
  }

  static Stream<String> failingSteps() {
    return Stream.of("findByAddress", "resolve", "issue");
  }

  @ParameterizedTest
  @MethodSource("failingSteps")
  void lookupFailureIsContainedAndLogged(String step) {
    RuntimeException failure = new IllegalStateException("secret-detail " + ADDRESS + " " + TOKEN);
    switch (step) {
      case "findByAddress" -> when(credentials.findByAddress(ADDRESS)).thenThrow(failure);
      case "resolve" -> {
        when(credentials.findByAddress(ADDRESS)).thenReturn(Optional.of(credential));
        when(whoService.resolveDetailed(any(Credential.class))).thenThrow(failure);
      }
      default -> {
        known(true);
        when(repository.issue(any(), anyString(), anyString(), any(), any(), any()))
            .thenThrow(failure);
      }
    }

    issuer.issue(ADDRESS, TOKEN);

    verifyNoInteractions(sender);
    verify(repository, never()).delete(any(), anyString());
    assertOneSafeWarning(IllegalStateException.class.getName(), !step.equals("findByAddress"));
  }

  @Test
  void anAddressStartingWithAnAtSignIsStillLookedUp() {
    when(credentials.findByAddress("@example.com")).thenReturn(Optional.empty());

    issuer.issue("@example.com", TOKEN);

    verify(credentials).findByAddress("@example.com");
  }

  @Test
  void anAddressWithAControlCharacterOrTooLongStopsBeforeTheLookup() {
    assertThat(issuer.issue("owner\u0000@example.com", TOKEN))
        .isEqualTo(EmailLinkOutcome.UNKNOWN_ADDRESS);
    assertThat(issuer.issue("a".repeat(310) + "@example.com", TOKEN))
        .isEqualTo(EmailLinkOutcome.UNKNOWN_ADDRESS);

    verifyNoInteractions(credentials, repository, sender, whoService);
  }

  @Test
  void aTextWithoutAnAtSignOrNullAddressStopsBeforeTheLookup() {
    issuer.issue("not-an-address", TOKEN);
    issuer.issue(null, TOKEN);

    verifyNoInteractions(credentials, repository, sender, whoService);
  }

  @Test
  void failedCleanupAfterAFailedSendIsLoggedWithoutTheAddressOrToken() {
    known(true);
    when(repository.issue(any(), anyString(), anyString(), any(), any(), any())).thenReturn(true);
    doThrow(new IllegalStateException("smtp down")).when(sender).send(any(EmailLinkMessage.class));
    doThrow(new IllegalArgumentException("secret-detail " + ADDRESS))
        .when(repository)
        .delete(any(), anyString());

    issuer.issue(ADDRESS, TOKEN);

    assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.WARN).hasSize(2);
    String cleanup =
        logs.list.stream()
            .filter(e -> e.getLevel() == Level.WARN)
            .map(ILoggingEvent::getFormattedMessage)
            .filter(m -> m.contains(IllegalArgumentException.class.getName()))
            .findFirst()
            .orElseThrow();
    assertThat(cleanup).contains(credential.id().toString());
    for (ILoggingEvent event : logs.list) {
      assertThat(event.getFormattedMessage())
          .doesNotContain(ADDRESS)
          .doesNotContain(TOKEN)
          .doesNotContain("secret-detail");
      assertThat(event.getThrowableProxy()).isNull();
    }
  }

  @Test
  void knownActiveAddressIsSent() {
    known(true);
    when(repository.issue(any(), anyString(), anyString(), any(), any(), any())).thenReturn(true);

    assertThat(issuer.issue(ADDRESS, TOKEN)).isEqualTo(EmailLinkOutcome.SENT);
  }

  @Test
  void unknownAddressIsUnknown() {
    when(credentials.findByAddress(ADDRESS)).thenReturn(Optional.empty());

    assertThat(issuer.issue(ADDRESS, TOKEN)).isEqualTo(EmailLinkOutcome.UNKNOWN_ADDRESS);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   ", "not-an-address"})
  void textThatIsNotAnAddressIsUnknown(String address) {
    assertThat(issuer.issue(address, TOKEN)).isEqualTo(EmailLinkOutcome.UNKNOWN_ADDRESS);
  }

  @Test
  void nullAddressIsUnknown() {
    assertThat(issuer.issue(null, TOKEN)).isEqualTo(EmailLinkOutcome.UNKNOWN_ADDRESS);
  }

  @Test
  void inactiveIdentityIsInactive() {
    known(false);

    assertThat(issuer.issue(ADDRESS, TOKEN)).isEqualTo(EmailLinkOutcome.INACTIVE_IDENTITY);
  }

  private EmailLinkIssuer publishingTo(List<WhoEvent> events) {
    return new EmailLinkIssuer(
        credentials,
        whoService,
        repository,
        sender,
        new EmailLinkSettings("https://app.test/signin", TTL, RESEND, CODE_LENGTH, 5, SECRET),
        Clock.fixed(NOW, ZoneOffset.UTC),
        events::add);
  }

  @Test
  void anAddressWhoseIdentityIsGoneSendsNothingAndReportsTheIdentity() {
    UUID deletedIdentity = UUID.randomUUID();
    when(credentials.findByAddress(ADDRESS)).thenReturn(Optional.of(credential));
    when(whoService.resolveDetailed(any(Credential.class)))
        .thenReturn(new NoIdentity(deletedIdentity));
    List<WhoEvent> events = new ArrayList<>();

    assertThat(publishingTo(events).issue(ADDRESS, TOKEN))
        .isEqualTo(EmailLinkOutcome.INACTIVE_IDENTITY);

    verify(sender, never()).send(any());
    assertThat(events)
        .containsExactly(
            new SignInEmailRequested(
                NOW, ADDRESS, EmailLinkOutcome.INACTIVE_IDENTITY, deletedIdentity));
  }

  @Test
  void anInactiveIdentityIsReportedWithItsId() {
    UUID inactiveIdentity = UUID.randomUUID();
    when(credentials.findByAddress(ADDRESS)).thenReturn(Optional.of(credential));
    when(whoService.resolveDetailed(any(Credential.class)))
        .thenReturn(new Inactive(inactiveIdentity));
    List<WhoEvent> events = new ArrayList<>();

    publishingTo(events).issue(ADDRESS, TOKEN);

    assertThat(events)
        .containsExactly(
            new SignInEmailRequested(
                NOW, ADDRESS, EmailLinkOutcome.INACTIVE_IDENTITY, inactiveIdentity));
  }

  @Test
  void aListenerThatThrowsLeavesTheOutcomeAloneAndIsLoggedSafely() {
    known(true);
    when(repository.issue(any(), anyString(), anyString(), any(), any(), any())).thenReturn(true);
    EmailLinkIssuer throwing =
        new EmailLinkIssuer(
            credentials,
            whoService,
            repository,
            sender,
            new EmailLinkSettings("https://app.test/signin", TTL, RESEND, CODE_LENGTH, 5, SECRET),
            Clock.fixed(NOW, ZoneOffset.UTC),
            event -> {
              throw new IllegalStateException("secret-detail");
            });

    assertThat(throwing.issue(ADDRESS, TOKEN)).isEqualTo(EmailLinkOutcome.SENT);

    verify(sender).send(any());
    assertOneSafeWarning("IllegalStateException", false);
  }

  @Test
  void aMissingResolutionIsTreatedAsNotLinked() {
    when(credentials.findByAddress(ADDRESS)).thenReturn(Optional.of(credential));

    // the mocked WhoService stubs nothing, so resolveDetailed answers null
    assertThat(issuer.issue(ADDRESS, TOKEN)).isEqualTo(EmailLinkOutcome.INACTIVE_IDENTITY);
    verify(sender, never()).send(any());
  }

  @Test
  void withinResendIntervalIsSuppressed() {
    known(true);
    when(repository.issue(any(), anyString(), anyString(), any(), any(), any())).thenReturn(false);

    assertThat(issuer.issue(ADDRESS, TOKEN)).isEqualTo(EmailLinkOutcome.RESEND_SUPPRESSED);
  }

  @Test
  void failedSendIsSendFailed() {
    known(true);
    when(repository.issue(any(), anyString(), anyString(), any(), any(), any())).thenReturn(true);
    doThrow(new IllegalStateException("smtp down")).when(sender).send(any(EmailLinkMessage.class));

    assertThat(issuer.issue(ADDRESS, TOKEN)).isEqualTo(EmailLinkOutcome.SEND_FAILED);
    verify(repository).delete(eq(credential.id()), anyString());
  }

  @ParameterizedTest
  @MethodSource("failingSteps")
  void aFailedLookupOrStoreIsSendFailedNotThrown(String step) {
    RuntimeException failure = new IllegalStateException("boom");
    switch (step) {
      case "findByAddress" -> when(credentials.findByAddress(ADDRESS)).thenThrow(failure);
      case "resolve" -> {
        when(credentials.findByAddress(ADDRESS)).thenReturn(Optional.of(credential));
        when(whoService.resolveDetailed(any(Credential.class))).thenThrow(failure);
      }
      default -> {
        known(true);
        when(repository.issue(any(), anyString(), anyString(), any(), any(), any()))
            .thenThrow(failure);
      }
    }

    assertThat(issuer.issue(ADDRESS, TOKEN)).isEqualTo(EmailLinkOutcome.SEND_FAILED);
  }

  private EmailLinkIssuer observedBy(ObservationRegistry observations) {
    return new EmailLinkIssuer(
        credentials,
        whoService,
        repository,
        sender,
        new EmailLinkSettings("https://app.test/signin", TTL, RESEND, CODE_LENGTH, 5, SECRET),
        Clock.fixed(NOW, ZoneOffset.UTC),
        WhoEventPublisher.NOOP,
        observations);
  }

  private static ObservationRegistry handledBy(ObservationHandler<Observation.Context> handler) {
    ObservationRegistry registry = ObservationRegistry.create();
    registry.observationConfig().observationHandler(handler);
    return registry;
  }

  private void assertObservedOnce(TestObservationRegistry registry, String outcome) {
    TestObservationRegistryAssert.assertThat(registry)
        .hasNumberOfObservationsWithNameEqualTo(EmailLinkIssuer.ISSUE_OBSERVATION, 1)
        .hasObservationWithNameEqualTo(EmailLinkIssuer.ISSUE_OBSERVATION)
        .that()
        .hasContextualNameEqualTo("who issue sign-in email")
        .hasLowCardinalityKeyValue("outcome", outcome)
        .hasBeenStarted()
        .hasBeenStopped();
  }

  @Test
  void sendingAnEmailIsOneObservationWithOutcomeSent() {
    known(true);
    when(repository.issue(any(), anyString(), anyString(), any(), any(), any())).thenReturn(true);
    TestObservationRegistry registry = TestObservationRegistry.create();

    assertThat(observedBy(registry).issue(ADDRESS, TOKEN)).isEqualTo(EmailLinkOutcome.SENT);

    assertObservedOnce(registry, "sent");
  }

  @Test
  void anUnknownAddressIsObservedAsUnknownAddress() {
    TestObservationRegistry registry = TestObservationRegistry.create();

    observedBy(registry).issue(ADDRESS, TOKEN);

    assertObservedOnce(registry, "unknown-address");
  }

  @Test
  void aFailedSendIsObservedAsSendFailed() {
    known(true);
    when(repository.issue(any(), anyString(), anyString(), any(), any(), any())).thenReturn(true);
    doThrow(new IllegalStateException("smtp down")).when(sender).send(any(EmailLinkMessage.class));
    TestObservationRegistry registry = TestObservationRegistry.create();

    assertThat(observedBy(registry).issue(ADDRESS, TOKEN)).isEqualTo(EmailLinkOutcome.SEND_FAILED);

    assertObservedOnce(registry, "send-failed");
  }

  @Test
  void theObservationCarriesOnlyTheOutcomeAndNeverTheAddress() {
    known(true);
    when(repository.issue(any(), anyString(), anyString(), any(), any(), any())).thenReturn(true);
    List<Observation.Context> stopped = new ArrayList<>();

    observedBy(
            handledBy(
                new ObservationHandler<>() {
                  @Override
                  public boolean supportsContext(Observation.Context context) {
                    return true;
                  }

                  @Override
                  public void onStop(Observation.Context context) {
                    stopped.add(context);
                  }
                }))
        .issue(ADDRESS, TOKEN);

    assertThat(stopped).hasSize(1);
    Observation.Context context = stopped.getFirst();
    assertThat(context.getLowCardinalityKeyValues().stream().map(KeyValue::getKey))
        .containsExactly("outcome");
    assertThat(context.getHighCardinalityKeyValues()).isEmpty();
    assertThat(context.toString()).doesNotContain(ADDRESS).doesNotContain(TOKEN);
  }

  @Test
  void aFailingObservationHandlerLeavesTheOutcomeAlone() {
    known(true);
    when(repository.issue(any(), anyString(), anyString(), any(), any(), any())).thenReturn(true);
    ObservationRegistry failing =
        handledBy(
            new ObservationHandler<>() {
              @Override
              public boolean supportsContext(Observation.Context context) {
                return true;
              }

              @Override
              public void onStart(Observation.Context context) {
                throw new IllegalStateException("handler start");
              }

              @Override
              public void onStop(Observation.Context context) {
                throw new IllegalStateException("handler stop");
              }
            });

    assertThat(observedBy(failing).issue(ADDRESS, TOKEN)).isEqualTo(EmailLinkOutcome.SENT);
    verify(sender).send(any(EmailLinkMessage.class));
  }
}
