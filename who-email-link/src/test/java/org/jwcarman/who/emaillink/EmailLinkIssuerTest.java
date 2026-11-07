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
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.core.spi.Credential;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class EmailLinkIssuerTest {

  private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");
  private static final Duration TTL = Duration.ofMinutes(15);
  private static final Duration RESEND = Duration.ofSeconds(60);
  private static final String ADDRESS = "owner@example.com";
  private static final String TOKEN = "token-value";

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
          new EmailLinkSettings("https://app.test/signin", TTL, RESEND),
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
    when(whoService.resolve(any(Credential.class)))
        .thenReturn(
            active ? Optional.of(new WhoPrincipal(Identity.create(), Set.of())) : Optional.empty());
  }

  @Test
  void knownActiveAddressIsSentAFragmentLink() {
    known(true);
    when(repository.issue(any(), anyString(), any(), any(), any())).thenReturn(true);

    issuer.issue(ADDRESS, TOKEN);

    verify(repository)
        .issue(credential.id(), MessageDigests.sha256Hex(TOKEN), NOW, NOW.plus(TTL), RESEND);
    verify(sender).send(ADDRESS, "https://app.test/signin#token=" + TOKEN);
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
    when(repository.issue(any(), anyString(), any(), any(), any())).thenReturn(false);

    issuer.issue(ADDRESS, TOKEN);

    verifyNoInteractions(sender);
    verify(repository, never()).delete(any(), anyString());
  }

  @Test
  void failedSendRemovesTheTokenSoTheUserCanRetry() {
    known(true);
    when(repository.issue(any(), anyString(), any(), any(), any())).thenReturn(true);
    doThrow(new IllegalStateException("smtp down")).when(sender).send(anyString(), anyString());

    issuer.issue(ADDRESS, TOKEN);

    ArgumentCaptor<String> issuedHash = ArgumentCaptor.forClass(String.class);
    verify(repository).issue(any(), issuedHash.capture(), any(), any(), any());
    verify(repository).delete(credential.id(), issuedHash.getValue());
  }

  @Test
  void failedSendIsLoggedWithoutTheAddressOrToken() {
    known(true);
    when(repository.issue(any(), anyString(), any(), any(), any())).thenReturn(true);
    doThrow(new IllegalStateException("secret-detail " + ADDRESS))
        .when(sender)
        .send(anyString(), anyString());

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
        when(whoService.resolve(any(Credential.class))).thenThrow(failure);
      }
      default -> {
        known(true);
        when(repository.issue(any(), anyString(), any(), any(), any())).thenThrow(failure);
      }
    }

    issuer.issue(ADDRESS, TOKEN);

    verifyNoInteractions(sender);
    verify(repository, never()).delete(any(), anyString());
    assertOneSafeWarning(IllegalStateException.class.getName(), !step.equals("findByAddress"));
  }
}
