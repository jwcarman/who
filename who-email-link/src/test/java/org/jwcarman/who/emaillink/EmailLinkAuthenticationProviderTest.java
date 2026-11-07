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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.core.spi.Credential;
import org.jwcarman.who.spring.security.WhoAuthenticationToken;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.ott.DefaultOneTimeToken;
import org.springframework.security.authentication.ott.InvalidOneTimeTokenException;
import org.springframework.security.authentication.ott.OneTimeTokenAuthenticationToken;
import org.springframework.security.authentication.ott.OneTimeTokenService;
import org.springframework.security.core.Authentication;

class EmailLinkAuthenticationProviderTest {

  private final OneTimeTokenService tokenService = mock(OneTimeTokenService.class);
  private final WhoService whoService = mock(WhoService.class);
  private final EmailLinkAuthenticationProvider provider =
      new EmailLinkAuthenticationProvider(tokenService, whoService);

  private final UUID credentialId = UUID.randomUUID();
  private final OneTimeTokenAuthenticationToken request =
      OneTimeTokenAuthenticationToken.unauthenticated("value");

  @Test
  void supportsOnlyOneTimeTokens() {
    assertThat(provider.supports(OneTimeTokenAuthenticationToken.class)).isTrue();
    assertThat(provider.supports(UsernamePasswordAuthenticationToken.class)).isFalse();
  }

  @Test
  void validTokenGivesWhoTokenWithCredentialId() {
    WhoPrincipal principal = new WhoPrincipal(Identity.create(), Set.of("read"));
    when(tokenService.consume(request))
        .thenReturn(new DefaultOneTimeToken("value", credentialId.toString(), Instant.now()));
    when(whoService.resolve(any(Credential.class))).thenReturn(Optional.of(principal));

    Authentication result = provider.authenticate(request);

    assertThat(result).isInstanceOf(WhoAuthenticationToken.class);
    WhoAuthenticationToken who = (WhoAuthenticationToken) result;
    assertThat(who.getPrincipal()).isEqualTo(principal);
    assertThat(who.credentialId()).contains(credentialId);
    ArgumentCaptor<Credential> credential = ArgumentCaptor.forClass(Credential.class);
    verify(whoService).resolve(credential.capture());
    assertThat(credential.getValue().id()).isEqualTo(credentialId);
  }

  @Test
  void consumedOrUnknownTokenIsInvalid() {
    when(tokenService.consume(request)).thenReturn(null);

    assertThatThrownBy(() -> provider.authenticate(request))
        .isInstanceOf(InvalidOneTimeTokenException.class)
        .hasMessage("invalid_token");
    verify(whoService, never()).resolve(any(Credential.class));
  }

  @Test
  void inactiveIdentityIsInvalid() {
    when(tokenService.consume(request))
        .thenReturn(new DefaultOneTimeToken("value", credentialId.toString(), Instant.now()));
    when(whoService.resolve(any(Credential.class))).thenReturn(Optional.empty());

    assertThatThrownBy(() -> provider.authenticate(request))
        .isInstanceOf(InvalidOneTimeTokenException.class)
        .hasMessage("invalid_token");
  }

  @Test
  void nonUuidUsernameIsInvalid() {
    when(tokenService.consume(request))
        .thenReturn(new DefaultOneTimeToken("value", "not-a-uuid", Instant.now()));

    assertThatThrownBy(() -> provider.authenticate(request))
        .isInstanceOf(InvalidOneTimeTokenException.class)
        .hasMessage("invalid_token");
  }

  @Test
  void sentinelTokenIsInvalidWithoutConsuming() {
    OneTimeTokenAuthenticationToken sentinel =
        OneTimeTokenAuthenticationToken.unauthenticated(
            JsonOneTimeTokenAuthenticationConverter.NO_TOKEN);

    assertThatThrownBy(() -> provider.authenticate(sentinel))
        .isInstanceOf(InvalidOneTimeTokenException.class)
        .hasMessage("invalid_token");
    verifyNoInteractions(tokenService, whoService);
  }
}
