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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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

import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.crypto.MessageDigests;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.event.SignInFailed;
import org.jwcarman.who.core.event.SignInFailureReason;
import org.jwcarman.who.core.event.SignInMethod;
import org.jwcarman.who.core.event.WhoEvent;
import org.jwcarman.who.core.service.Resolution.Inactive;
import org.jwcarman.who.core.service.Resolution.Resolved;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.core.spi.Credential;
import org.jwcarman.who.emaillink.EmailSignInProof.EmailCode;
import org.jwcarman.who.emaillink.EmailSignInProof.LinkToken;
import org.jwcarman.who.emaillink.EmailSignInProof.Malformed;
import org.jwcarman.who.spring.security.WhoAuthenticationToken;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.ott.InvalidOneTimeTokenException;
import org.springframework.security.authentication.ott.OneTimeTokenAuthenticationToken;
import org.springframework.security.core.Authentication;

class EmailLinkAuthenticationProviderTest {

  private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");
  private static final String SECRET = "0123456789abcdef0123456789abcdef";
  private static final EmailLinkSettings SETTINGS =
      new EmailLinkSettings(
          "https://app.example.com/sign-in",
          Duration.ofMinutes(15),
          Duration.ofSeconds(60),
          6,
          5,
          SECRET);

  private final EmailLinkTokenRepository repository = mock(EmailLinkTokenRepository.class);
  private final WhoService whoService = mock(WhoService.class);
  private final EmailLinkAuthenticationProvider provider =
      new EmailLinkAuthenticationProvider(
          repository, SETTINGS, whoService, Clock.fixed(NOW, ZoneOffset.UTC));
  private final EmailCodeHasher hasher = new EmailCodeHasher(SECRET);

  private final UUID credentialId = UUID.randomUUID();
  private final WhoPrincipal principal = new WhoPrincipal(Identity.create(), Set.of("read"));

  private static EmailSignInAuthenticationToken signIn(EmailSignInProof proof) {
    return new EmailSignInAuthenticationToken(proof);
  }

  private void assertInvalid(EmailSignInProof proof) {
    EmailSignInAuthenticationToken token = signIn(proof);
    assertThatThrownBy(() -> provider.authenticate(token))
        .isInstanceOf(InvalidOneTimeTokenException.class)
        .hasMessage("invalid_token");
  }

  private void assertSignedIn(Authentication result) {
    assertThat(result).isInstanceOf(WhoAuthenticationToken.class);
    WhoAuthenticationToken who = (WhoAuthenticationToken) result;
    assertThat(who.getPrincipal()).isEqualTo(principal);
    assertThat(who.credentialId()).contains(credentialId);
    ArgumentCaptor<Credential> credential = ArgumentCaptor.forClass(Credential.class);
    verify(whoService).resolveDetailed(credential.capture());
    assertThat(credential.getValue().id()).isEqualTo(credentialId);
  }

  @Test
  void supportsOnlyEmailSignIns() {
    assertThat(provider.supports(EmailSignInAuthenticationToken.class)).isTrue();
    assertThat(provider.supports(OneTimeTokenAuthenticationToken.class)).isFalse();
    assertThat(provider.supports(UsernamePasswordAuthenticationToken.class)).isFalse();
  }

  @Test
  void validLinkTokenGivesWhoTokenWithCredentialId() {
    when(repository.consume(MessageDigests.sha256Hex("value"), NOW))
        .thenReturn(Optional.of(credentialId));
    when(whoService.resolveDetailed(any(Credential.class))).thenReturn(new Resolved(principal));

    assertSignedIn(provider.authenticate(signIn(new LinkToken("value"))));
  }

  @Test
  void unknownLinkTokenIsInvalid() {
    when(repository.consume(anyString(), any())).thenReturn(Optional.empty());

    assertInvalid(new LinkToken("value"));
    verify(whoService, never()).resolveDetailed(any(Credential.class));
  }

  @Test
  void validCodeConsumesTheTokenByItsHash() {
    String hash = hasher.hash(credentialId, "K7F2QM");
    when(repository.attemptCode("owner@example.com", 5, NOW))
        .thenReturn(Optional.of(new EmailCodeAttempt(credentialId, hash)));
    when(repository.consumeCode(credentialId, hash, NOW)).thenReturn(Optional.of(credentialId));
    when(whoService.resolveDetailed(any(Credential.class))).thenReturn(new Resolved(principal));

    assertSignedIn(provider.authenticate(signIn(new EmailCode(" Owner@Example.com ", "k7f-2qm"))));
    verify(repository).consumeCode(credentialId, hash, NOW);
  }

  @Test
  void codeBurntBetweenAttemptAndConsumeIsInvalid() {
    String hash = hasher.hash(credentialId, "K7F2QM");
    when(repository.attemptCode("owner@example.com", 5, NOW))
        .thenReturn(Optional.of(new EmailCodeAttempt(credentialId, hash)));
    when(repository.consumeCode(credentialId, hash, NOW)).thenReturn(Optional.empty());

    assertInvalid(new EmailCode("owner@example.com", "K7F2QM"));
    verify(whoService, never()).resolveDetailed(any(Credential.class));
  }

  @Test
  void wrongCodeCountsAnAttemptButConsumesNothing() {
    when(repository.attemptCode("owner@example.com", 5, NOW))
        .thenReturn(
            Optional.of(new EmailCodeAttempt(credentialId, hasher.hash(credentialId, "K7F2QM"))));

    assertInvalid(new EmailCode("owner@example.com", "K7F2QN"));
    verify(repository).attemptCode("owner@example.com", 5, NOW);
    verify(repository, never()).consumeCode(any(), anyString(), any());
    verifyNoInteractions(whoService);
  }

  @Test
  void noAttemptLeftIsInvalid() {
    when(repository.attemptCode("owner@example.com", 5, NOW)).thenReturn(Optional.empty());

    assertInvalid(new EmailCode("owner@example.com", "K7F2QM"));
    verify(repository, never()).consumeCode(any(), anyString(), any());
    verifyNoInteractions(whoService);
  }

  @Test
  void malformedCodeShapeNeverTouchesTheRepository() {
    assertInvalid(new EmailCode("owner@example.com", "K7F2QU"));
    verifyNoInteractions(repository, whoService);
  }

  @Test
  void blankAddressNeverTouchesTheRepository() {
    assertInvalid(new EmailCode(" ", "K7F2QM"));
    verifyNoInteractions(repository, whoService);
  }

  @Test
  void addressWithANulNeverTouchesTheRepository() {
    assertInvalid(new EmailCode("owner\u0000@example.com", "K7F2QM"));
    verifyNoInteractions(repository, whoService);
  }

  @Test
  void addressLongerThan320CharactersNeverTouchesTheRepository() {
    String address = "a".repeat(309) + "@example.com";
    assertThat(address).hasSize(321);

    assertInvalid(new EmailCode(address, "K7F2QM"));
    verifyNoInteractions(repository, whoService);
  }

  @Test
  void malformedIsInvalidWithoutTouchingAnything() {
    assertInvalid(new Malformed());
    verifyNoInteractions(repository, whoService);
  }

  @Test
  void inactiveIdentityIsInvalid() {
    when(repository.consume(anyString(), any())).thenReturn(Optional.of(credentialId));
    when(whoService.resolveDetailed(any(Credential.class)))
        .thenReturn(new Inactive(UUID.randomUUID()));

    assertInvalid(new LinkToken("value"));
  }

  @Test
  void aMissingResolutionIsTreatedAsNotLinked() {
    List<WhoEvent> events = new ArrayList<>();
    EmailLinkAuthenticationProvider publishing =
        new EmailLinkAuthenticationProvider(
            repository, SETTINGS, whoService, Clock.fixed(NOW, ZoneOffset.UTC), events::add);
    when(repository.consume(anyString(), any())).thenReturn(Optional.of(credentialId));
    EmailSignInAuthenticationToken token = signIn(new LinkToken("value"));

    // the mocked WhoService stubs nothing, so resolveDetailed answers null
    assertThatThrownBy(() -> publishing.authenticate(token))
        .isInstanceOf(InvalidOneTimeTokenException.class)
        .hasMessage("invalid_token");

    assertThat(events)
        .hasSize(1)
        .first()
        .isInstanceOfSatisfying(
            SignInFailed.class,
            failed -> {
              assertThat(failed.method()).isEqualTo(SignInMethod.EMAIL_LINK);
              assertThat(failed.reason()).isEqualTo(SignInFailureReason.INVALID_LINK);
              assertThat(failed.identityId()).isNull();
              assertThat(failed.credentialId()).isEqualTo(credentialId);
            });
  }
}
