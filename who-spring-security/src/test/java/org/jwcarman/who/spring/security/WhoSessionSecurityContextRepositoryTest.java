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
package org.jwcarman.who.spring.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.core.spi.Credential;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;

class WhoSessionSecurityContextRepositoryTest {

  private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");

  private final WhoService whoService = mock(WhoService.class);
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final WhoSessionSecurityContextRepository repository =
      new WhoSessionSecurityContextRepository(whoService, clock);
  private final MockHttpServletRequest request = new MockHttpServletRequest();
  private final MockHttpServletResponse response = new MockHttpServletResponse();

  private final Identity identity = Identity.create();
  private final UUID credId = UUID.randomUUID();

  private SecurityContext contextFor(WhoPrincipal principal) {
    SecurityContext context = new SecurityContextImpl();
    context.setAuthentication(new WhoAuthenticationToken(principal, credId));
    return context;
  }

  @Test
  void savingAWhoTokenStoresOnlyIds() {
    repository.saveContext(contextFor(new WhoPrincipal(identity, Set.of("a"))), request, response);

    Object stored =
        request
            .getSession(false)
            .getAttribute(WhoSessionSecurityContextRepository.SESSION_ATTRIBUTE);
    assertThat(stored).isEqualTo(new WhoSession(identity.id(), credId, NOW));
  }

  @Test
  void loadingResolvesAFreshPrincipal() {
    request.setSession(sessionWith(new WhoSession(identity.id(), credId, NOW)));
    when(whoService.resolve(any(Credential.class)))
        .thenReturn(Optional.of(new WhoPrincipal(identity, Set.of("b"))));

    SecurityContext loaded = repository.loadDeferredContext(request).get();

    assertThat(loaded.getAuthentication()).isInstanceOf(WhoAuthenticationToken.class);
    WhoAuthenticationToken token = (WhoAuthenticationToken) loaded.getAuthentication();
    assertThat(token.getAuthorities()).containsExactly(new SimpleGrantedAuthority("b"));
    assertThat(token.credentialId()).contains(credId);
  }

  @Test
  void inactiveOrUnlinkedEndsTheSession() {
    MockHttpSession session = sessionWith(new WhoSession(identity.id(), credId, NOW));
    request.setSession(session);
    when(whoService.resolve(any(Credential.class))).thenReturn(Optional.empty());

    SecurityContext loaded = repository.loadDeferredContext(request).get();

    assertThat(loaded.getAuthentication()).isNull();
    assertThat(session.isInvalid()).isTrue();
  }

  @Test
  void relinkedCredentialEndsTheSession() {
    MockHttpSession session = sessionWith(new WhoSession(identity.id(), credId, NOW));
    request.setSession(session);
    when(whoService.resolve(any(Credential.class)))
        .thenReturn(Optional.of(new WhoPrincipal(Identity.create(), Set.of("b"))));

    SecurityContext loaded = repository.loadDeferredContext(request).get();

    assertThat(loaded.getAuthentication()).isNull();
    assertThat(session.isInvalid()).isTrue();
  }

  @Test
  void nonWhoAuthenticationIsNotStored() {
    SecurityContext context = new SecurityContextImpl();
    context.setAuthentication(new TestingAuthenticationToken("u", "p", "ROLE_X"));

    repository.saveContext(context, request, response);

    assertThat(request.getSession(false)).isNull();
    assertThat(repository.currentSession(request)).isEmpty();
  }

  @Test
  void resavingKeepsSignedInAt() {
    Instant earlier = NOW.minusSeconds(3600);
    request.setSession(sessionWith(new WhoSession(identity.id(), credId, earlier)));

    repository.saveContext(contextFor(new WhoPrincipal(identity, Set.of("a"))), request, response);

    assertThat(repository.currentSession(request))
        .contains(new WhoSession(identity.id(), credId, earlier));
  }

  @Test
  void loadingWithoutASessionDoesNotCreateOne() {
    SecurityContext loaded = repository.loadDeferredContext(request).get();

    assertThat(loaded.getAuthentication()).isNull();
    assertThat(request.getSession(false)).isNull();
    assertThat(repository.containsContext(request)).isFalse();
  }

  private static MockHttpSession sessionWith(WhoSession whoSession) {
    MockHttpSession session = new MockHttpSession();
    session.setAttribute(WhoSessionSecurityContextRepository.SESSION_ATTRIBUTE, whoSession);
    return session;
  }
}
