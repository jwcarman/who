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
package org.jwcarman.who.passkey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.webauthn.api.AuthenticatorAssertionResponse;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredential;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialType;
import org.springframework.security.web.webauthn.authentication.WebAuthnAuthenticationRequestToken;
import org.springframework.security.web.webauthn.management.RelyingPartyAuthenticationRequest;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;

import com.webauthn4j.verifier.exception.BadSignatureException;

class WhoPasskeyAuthenticationProviderTest {

  private final WebAuthnRelyingPartyOperations rpOperations =
      mock(WebAuthnRelyingPartyOperations.class);
  private final JdbcWhoPasskeyCredentialRepository credentialRepository =
      mock(JdbcWhoPasskeyCredentialRepository.class);
  private final WhoService whoService = mock(WhoService.class);
  private final WhoPasskeyAuthenticationProvider provider =
      new WhoPasskeyAuthenticationProvider(rpOperations, credentialRepository, whoService);

  private final Bytes rawId = Bytes.random();
  private final UUID credentialUuid = UUID.randomUUID();
  private final RelyingPartyAuthenticationRequest request = request(rawId);
  private final WebAuthnAuthenticationRequestToken token =
      new WebAuthnAuthenticationRequestToken(request);

  private static RelyingPartyAuthenticationRequest request(Bytes rawId) {
    PublicKeyCredential<AuthenticatorAssertionResponse> credential =
        PublicKeyCredential.<AuthenticatorAssertionResponse>builder()
            .id(rawId.toBase64UrlString())
            .rawId(rawId)
            .type(PublicKeyCredentialType.PUBLIC_KEY)
            .response(
                AuthenticatorAssertionResponse.builder()
                    .authenticatorData(Bytes.random())
                    .clientDataJSON(Bytes.random())
                    .signature(Bytes.random())
                    .build())
            .build();
    PublicKeyCredentialRequestOptions options =
        PublicKeyCredentialRequestOptions.builder()
            .challenge(Bytes.random())
            .rpId("app.test")
            .build();
    return new RelyingPartyAuthenticationRequest(options, credential);
  }

  private void verifiedAssertion() {
    when(rpOperations.authenticate(request))
        .thenReturn(
            ImmutablePublicKeyCredentialUserEntity.builder()
                .id(Bytes.random())
                .name("owner@example.com")
                .displayName("owner@example.com")
                .build());
  }

  @Test
  void supportsOnlyWebAuthnRequests() {
    assertThat(provider.supports(WebAuthnAuthenticationRequestToken.class)).isTrue();
    assertThat(provider.supports(UsernamePasswordAuthenticationToken.class)).isFalse();
  }

  @Test
  void validAssertionGivesWhoTokenWithCredentialUuid() {
    WhoPrincipal principal = new WhoPrincipal(Identity.create(), Set.of("read"));
    verifiedAssertion();
    when(credentialRepository.credentialUuid(rawId)).thenReturn(Optional.of(credentialUuid));
    when(whoService.resolve(any(Credential.class))).thenReturn(Optional.of(principal));

    Authentication result = provider.authenticate(token);

    assertThat(result).isInstanceOf(WhoAuthenticationToken.class);
    WhoAuthenticationToken who = (WhoAuthenticationToken) result;
    assertThat(who.getPrincipal()).isEqualTo(principal);
    assertThat(who.credentialId()).contains(credentialUuid);
    ArgumentCaptor<Credential> credential = ArgumentCaptor.forClass(Credential.class);
    verify(whoService).resolve(credential.capture());
    assertThat(credential.getValue().id()).isEqualTo(credentialUuid);
  }

  @Test
  void unknownCredentialIsInvalid() {
    verifiedAssertion();
    when(credentialRepository.credentialUuid(rawId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> provider.authenticate(token))
        .isInstanceOf(BadCredentialsException.class)
        .hasMessage("invalid_credential");
    verify(whoService, never()).resolve(any(Credential.class));
  }

  @Test
  void inactiveIdentityIsInvalid() {
    verifiedAssertion();
    when(credentialRepository.credentialUuid(rawId)).thenReturn(Optional.of(credentialUuid));
    when(whoService.resolve(any(Credential.class))).thenReturn(Optional.empty());

    assertThatThrownBy(() -> provider.authenticate(token))
        .isInstanceOf(BadCredentialsException.class)
        .hasMessage("invalid_credential");
  }

  @Test
  void verificationExceptionBecomesInvalidCredential() {
    BadSignatureException failure = new BadSignatureException("signature mismatch");
    when(rpOperations.authenticate(request)).thenThrow(failure);

    assertThatThrownBy(() -> provider.authenticate(token))
        .isInstanceOf(BadCredentialsException.class)
        .hasMessage("invalid_credential")
        .hasNoCause();
    verify(credentialRepository, never()).credentialUuid(any());
    verify(whoService, never()).resolve(any(Credential.class));
  }

  @Test
  void anyRuntimeExceptionBecomesInvalidCredential() {
    verifiedAssertion();
    when(credentialRepository.credentialUuid(rawId))
        .thenThrow(new DataRetrievalFailureException("database is down"));

    assertThatThrownBy(() -> provider.authenticate(token))
        .isInstanceOf(BadCredentialsException.class)
        .hasMessage("invalid_credential")
        .hasNoCause();
  }

  @Test
  void failureCarriesNoCause() {
    // Spring's relying party names the credential id when it does not know it
    when(rpOperations.authenticate(request))
        .thenThrow(
            new IllegalArgumentException(
                "Unable to find CredentialRecord with id " + rawId.toBase64UrlString()));

    assertThatThrownBy(() -> provider.authenticate(token))
        .isInstanceOf(BadCredentialsException.class)
        .hasMessage("invalid_credential")
        .satisfies(e -> assertThat(e.getCause()).isNull())
        .satisfies(e -> assertThat(e.toString()).doesNotContain(rawId.toBase64UrlString()));
  }
}
