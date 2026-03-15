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
package org.jwcarman.who.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.spring.security.WhoAuthenticationToken;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

@ExtendWith(MockitoExtension.class)
class WhoJwtAuthenticationConverterTest {

  private static final String ISSUER = "https://issuer.example.com";
  private static final String SUBJECT = "user-123";

  @Mock private JwtCredentialRepository jwtCredentialRepository;

  @Mock private WhoService whoService;

  private WhoJwtAuthenticationConverter converter;

  @BeforeEach
  void setUp() {
    converter = new WhoJwtAuthenticationConverter(jwtCredentialRepository, whoService);
  }

  @Test
  void returnsNullWhenCredentialNotFound() {
    Jwt jwt = buildJwt(ISSUER, SUBJECT);
    when(jwtCredentialRepository.findByIssuerAndSubject(ISSUER, SUBJECT))
        .thenReturn(Optional.empty());

    assertThat(converter.convert(jwt)).isNull();
  }

  @Test
  void returnsNullWhenWhoServiceReturnsEmpty() {
    JwtCredential credential = JwtCredential.create(ISSUER, SUBJECT);
    Jwt jwt = buildJwt(ISSUER, SUBJECT);

    when(jwtCredentialRepository.findByIssuerAndSubject(ISSUER, SUBJECT))
        .thenReturn(Optional.of(credential));
    when(whoService.resolve(credential)).thenReturn(Optional.empty());

    assertThat(converter.convert(jwt)).isNull();
  }

  @Test
  void returnsAuthenticationTokenForValidCredential() {
    JwtCredential credential = JwtCredential.create(ISSUER, SUBJECT);
    WhoPrincipal principal = new WhoPrincipal(Identity.create(), Set.of("read:data", "write:data"));
    Jwt jwt = buildJwt(ISSUER, SUBJECT);

    when(jwtCredentialRepository.findByIssuerAndSubject(ISSUER, SUBJECT))
        .thenReturn(Optional.of(credential));
    when(whoService.resolve(credential)).thenReturn(Optional.of(principal));

    AbstractAuthenticationToken token = converter.convert(jwt);

    assertThat(token).isInstanceOf(WhoAuthenticationToken.class);
    assertThat(token.getPrincipal()).isSameAs(principal);
  }

  @Test
  void authoritiesMatchPermissionsInPrincipal() {
    JwtCredential credential = JwtCredential.create(ISSUER, SUBJECT);
    WhoPrincipal principal = new WhoPrincipal(Identity.create(), Set.of("read:data", "write:data"));
    Jwt jwt = buildJwt(ISSUER, SUBJECT);

    when(jwtCredentialRepository.findByIssuerAndSubject(ISSUER, SUBJECT))
        .thenReturn(Optional.of(credential));
    when(whoService.resolve(credential)).thenReturn(Optional.of(principal));

    AbstractAuthenticationToken token = converter.convert(jwt);

    assertThat(token).isNotNull();
    assertThat(token.getAuthorities())
        .extracting("authority")
        .containsExactlyInAnyOrder("read:data", "write:data");
  }

  @Test
  void getCredentialsReturnsNull() {
    JwtCredential credential = JwtCredential.create(ISSUER, SUBJECT);
    WhoPrincipal principal = new WhoPrincipal(Identity.create(), Set.of());
    Jwt jwt = buildJwt(ISSUER, SUBJECT);

    when(jwtCredentialRepository.findByIssuerAndSubject(ISSUER, SUBJECT))
        .thenReturn(Optional.of(credential));
    when(whoService.resolve(credential)).thenReturn(Optional.of(principal));

    WhoAuthenticationToken token = (WhoAuthenticationToken) converter.convert(jwt);

    assertThat(token).isNotNull();
    assertThat(token.getCredentials()).isNull();
  }

  private static Jwt buildJwt(String issuer, String subject) {
    Instant now = Instant.now();
    return new Jwt(
        "token-value",
        now,
        now.plusSeconds(3600),
        Map.of("alg", "RS256"),
        Map.of("iss", issuer, "sub", subject));
  }
}
