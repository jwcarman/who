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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.event.WhoEventPublisher;
import org.jwcarman.who.core.service.Resolution.NotLinked;
import org.jwcarman.who.core.service.Resolution.Resolved;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.federated.FederatedCredential;
import org.jwcarman.who.federated.FederatedCredentialRepository;
import org.jwcarman.who.spring.security.WhoAuthenticationToken;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

@ExtendWith(MockitoExtension.class)
class WhoJwtAuthenticationConverterTest {

  private static final String ISSUER = "https://issuer.example.com";
  private static final String SUBJECT = "user-123";

  @Mock private FederatedCredentialRepository federatedCredentialRepository;

  @Mock private WhoService whoService;

  private WhoJwtAuthenticationConverter converter;

  @BeforeEach
  void setUp() {
    converter = new WhoJwtAuthenticationConverter(federatedCredentialRepository, whoService);
  }

  @Test
  void throwsBadCredentialsWhenCredentialNotFound() {
    Jwt jwt = buildJwt(ISSUER, SUBJECT);
    when(federatedCredentialRepository.findByIssuerAndSubject(ISSUER, SUBJECT))
        .thenReturn(Optional.empty());

    assertThatExceptionOfType(BadCredentialsException.class)
        .isThrownBy(() -> converter.convert(jwt));
  }

  @Test
  void throwsBadCredentialsWhenWhoServiceReturnsEmpty() {
    FederatedCredential credential = FederatedCredential.create(ISSUER, SUBJECT);
    Jwt jwt = buildJwt(ISSUER, SUBJECT);

    when(federatedCredentialRepository.findByIssuerAndSubject(ISSUER, SUBJECT))
        .thenReturn(Optional.of(credential));
    when(whoService.resolveDetailed(credential)).thenReturn(new NotLinked());

    assertThatExceptionOfType(BadCredentialsException.class)
        .isThrownBy(() -> converter.convert(jwt));
  }

  @Test
  void returnsAuthenticationTokenForValidCredential() {
    FederatedCredential credential = FederatedCredential.create(ISSUER, SUBJECT);
    WhoPrincipal principal = new WhoPrincipal(Identity.create(), Set.of("read:data", "write:data"));
    Jwt jwt = buildJwt(ISSUER, SUBJECT);

    when(federatedCredentialRepository.findByIssuerAndSubject(ISSUER, SUBJECT))
        .thenReturn(Optional.of(credential));
    when(whoService.resolveDetailed(credential)).thenReturn(new Resolved(principal));

    AbstractAuthenticationToken token = converter.convert(jwt);

    assertThat(token).isInstanceOf(WhoAuthenticationToken.class);
    assertThat(token.getPrincipal()).isSameAs(principal);
  }

  @Test
  void authoritiesMatchPermissionsInPrincipal() {
    FederatedCredential credential = FederatedCredential.create(ISSUER, SUBJECT);
    WhoPrincipal principal = new WhoPrincipal(Identity.create(), Set.of("read:data", "write:data"));
    Jwt jwt = buildJwt(ISSUER, SUBJECT);

    when(federatedCredentialRepository.findByIssuerAndSubject(ISSUER, SUBJECT))
        .thenReturn(Optional.of(credential));
    when(whoService.resolveDetailed(credential)).thenReturn(new Resolved(principal));

    AbstractAuthenticationToken token = converter.convert(jwt);

    assertThat(token).isNotNull();
    assertThat(token.getAuthorities())
        .extracting("authority")
        .containsExactlyInAnyOrder("read:data", "write:data");
  }

  @Test
  void getCredentialsReturnsNull() {
    FederatedCredential credential = FederatedCredential.create(ISSUER, SUBJECT);
    WhoPrincipal principal = new WhoPrincipal(Identity.create(), Set.of());
    Jwt jwt = buildJwt(ISSUER, SUBJECT);

    when(federatedCredentialRepository.findByIssuerAndSubject(ISSUER, SUBJECT))
        .thenReturn(Optional.of(credential));
    when(whoService.resolveDetailed(credential)).thenReturn(new Resolved(principal));

    WhoAuthenticationToken token = (WhoAuthenticationToken) converter.convert(jwt);

    assertThat(token).isNotNull();
    assertThat(token.getCredentials()).isNull();
  }

  @Test
  void missingIssuerIsBadCredentials() {
    Jwt jwt = jwtWithClaims(Map.of("sub", SUBJECT));

    assertThatExceptionOfType(BadCredentialsException.class)
        .isThrownBy(() -> converter.convert(jwt))
        .withMessage("JWT is missing iss or sub");
  }

  @Test
  void blankSubjectIsUnauthorized() {
    Jwt jwt = jwtWithClaims(Map.of("iss", ISSUER, "sub", " "));

    assertThatExceptionOfType(BadCredentialsException.class)
        .isThrownBy(() -> converter.convert(jwt))
        .withMessage("JWT is missing iss or sub");
  }

  @Test
  void blankIssuerIsBadCredentials() {
    Jwt jwt = jwtWithClaims(Map.of("iss", " ", "sub", SUBJECT));

    assertThatExceptionOfType(BadCredentialsException.class)
        .isThrownBy(() -> converter.convert(jwt))
        .withMessage("JWT is missing iss or sub");
  }

  @Test
  void missingSubjectIsBadCredentials() {
    Jwt jwt = jwtWithClaims(Map.of("iss", ISSUER));

    assertThatExceptionOfType(BadCredentialsException.class)
        .isThrownBy(() -> converter.convert(jwt))
        .withMessage("JWT is missing iss or sub");
  }

  @Test
  void nonUrlIssuerIsLookedUp() {
    FederatedCredential credential = FederatedCredential.create("noir", SUBJECT);
    WhoPrincipal principal = new WhoPrincipal(Identity.create(), Set.of());
    Jwt jwt = buildJwt("noir", SUBJECT);

    when(federatedCredentialRepository.findByIssuerAndSubject("noir", SUBJECT))
        .thenReturn(Optional.of(credential));
    when(whoService.resolveDetailed(credential)).thenReturn(new Resolved(principal));

    assertThat(converter.convert(jwt).getPrincipal()).isSameAs(principal);
  }

  private static Jwt jwtWithClaims(Map<String, Object> claims) {
    Instant now = Instant.now();
    return new Jwt("token-value", now, now.plusSeconds(3600), Map.of("alg", "RS256"), claims);
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

  private static final Instant NOW = Instant.parse("2026-11-16T10:00:00Z");

  private WhoAuthenticationToken convertWithClaims(Map<String, Object> extra) {
    FederatedCredential credential = FederatedCredential.create(ISSUER, SUBJECT);
    WhoPrincipal principal = new WhoPrincipal(Identity.create(), Set.of("read:data"));
    when(federatedCredentialRepository.findByIssuerAndSubject(ISSUER, SUBJECT))
        .thenReturn(Optional.of(credential));
    when(whoService.resolveDetailed(credential)).thenReturn(new Resolved(principal));
    Map<String, Object> claims = new HashMap<>(Map.of("iss", ISSUER, "sub", SUBJECT));
    claims.putAll(extra);
    Jwt jwt = new Jwt("token-value", NOW, NOW.plusSeconds(3600), Map.of("alg", "RS256"), claims);
    WhoJwtAuthenticationConverter fixed =
        new WhoJwtAuthenticationConverter(
            federatedCredentialRepository,
            whoService,
            WhoEventPublisher.NOOP,
            Clock.fixed(NOW, ZoneOffset.UTC));
    return (WhoAuthenticationToken) fixed.convert(jwt);
  }

  @Test
  void auth_time_is_the_factor_time() {
    Instant authTime = NOW.minusSeconds(900);

    WhoAuthenticationToken token = convertWithClaims(Map.of("auth_time", authTime));

    assertThat(token.signInFactor())
        .hasValueSatisfying(
            factor -> {
              assertThat(factor.getAuthority()).isEqualTo(FactorGrantedAuthority.BEARER_AUTHORITY);
              assertThat(factor.getIssuedAt()).isEqualTo(authTime);
            });
  }

  @Test
  void a_numeric_auth_time_is_read_as_seconds() {
    WhoAuthenticationToken token =
        convertWithClaims(Map.of("auth_time", NOW.minusSeconds(60).getEpochSecond()));

    assertThat(token.signInFactor().map(FactorGrantedAuthority::getIssuedAt))
        .contains(NOW.minusSeconds(60));
  }

  @Test
  void no_auth_time_no_factor() {
    WhoAuthenticationToken token = convertWithClaims(Map.of());

    assertThat(token.signInFactor()).isEmpty();
    assertThat(token.getAuthorities()).extracting("authority").containsExactly("read:data");
  }

  @Test
  void an_auth_time_in_the_future_is_now() {
    WhoAuthenticationToken token = convertWithClaims(Map.of("auth_time", NOW.plusSeconds(30)));

    assertThat(token.signInFactor().map(FactorGrantedAuthority::getIssuedAt)).contains(NOW);
  }

  @Test
  void an_auth_time_more_than_a_minute_ahead_is_no_factor() {
    WhoAuthenticationToken token = convertWithClaims(Map.of("auth_time", NOW.plusSeconds(61)));

    assertThat(token.signInFactor()).isEmpty();
  }

  @Test
  void an_auth_time_in_milliseconds_is_no_factor() {
    WhoAuthenticationToken token =
        convertWithClaims(Map.of("auth_time", NOW.minusSeconds(60).toEpochMilli()));

    assertThat(token.signInFactor()).isEmpty();
  }

  @Test
  void an_auth_time_too_large_for_an_instant_is_no_factor() {
    WhoAuthenticationToken token = convertWithClaims(Map.of("auth_time", Long.MAX_VALUE));

    assertThat(token.signInFactor()).isEmpty();
  }

  @Test
  void an_unreadable_auth_time_is_no_factor() {
    WhoAuthenticationToken token = convertWithClaims(Map.of("auth_time", "yesterday"));

    assertThat(token.signInFactor()).isEmpty();
  }
}
