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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.http.HttpSession;

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
import org.springframework.security.web.context.HttpRequestResponseHolder;

class WhoSessionSecurityContextRepositoryTest {

  private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");

  private final WhoService whoService = mock(WhoService.class);
  private final MutableClock clock = new MutableClock(NOW);
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
  void resavingTheLoadedAuthenticationKeepsSignedInAt() {
    Instant earlier = NOW.minusSeconds(3600);
    request.setSession(sessionWith(new WhoSession(identity.id(), credId, earlier)));
    when(whoService.resolve(any(Credential.class)))
        .thenReturn(Optional.of(new WhoPrincipal(identity, Set.of("b"))));

    SecurityContext loaded = repository.loadDeferredContext(request).get();
    repository.saveContext(loaded, request, response);

    assertThat(repository.currentSession(request))
        .contains(new WhoSession(identity.id(), credId, earlier));
  }

  @Test
  void aNewSignInWithTheSameCredentialStampsANewSignedInAt() {
    WhoPrincipal principal = new WhoPrincipal(identity, Set.of("a"));
    repository.saveContext(contextFor(principal), request, response);
    clock.set(NOW.plusSeconds(600));

    repository.saveContext(contextFor(principal), request, response);

    assertThat(repository.currentSession(request))
        .contains(new WhoSession(identity.id(), credId, NOW.plusSeconds(600)));
  }

  @Test
  void savingAnEmptyContextRemovesTheSession() {
    request.setSession(sessionWith(new WhoSession(identity.id(), credId, NOW)));

    repository.saveContext(new SecurityContextImpl(), request, response);

    assertThat(repository.currentSession(request)).isEmpty();
  }

  @Test
  void savingANonWhoAuthenticationRemovesTheSession() {
    request.setSession(sessionWith(new WhoSession(identity.id(), credId, NOW)));
    SecurityContext context = new SecurityContextImpl();
    context.setAuthentication(new TestingAuthenticationToken("u", "p", "ROLE_X"));

    repository.saveContext(context, request, response);

    assertThat(repository.currentSession(request)).isEmpty();
  }

  @Test
  void isGeneratedWhenThereIsNoSessionOrItEnded() {
    assertThat(repository.loadDeferredContext(request).isGenerated()).isTrue();

    request.setSession(sessionWith(new WhoSession(identity.id(), credId, NOW)));
    when(whoService.resolve(any(Credential.class))).thenReturn(Optional.empty());
    assertThat(repository.loadDeferredContext(request).isGenerated()).isTrue();

    MockHttpServletRequest valid = new MockHttpServletRequest();
    valid.setSession(sessionWith(new WhoSession(identity.id(), credId, NOW)));
    when(whoService.resolve(any(Credential.class)))
        .thenReturn(Optional.of(new WhoPrincipal(identity, Set.of("b"))));
    assertThat(repository.loadDeferredContext(valid).isGenerated()).isFalse();
  }

  @Test
  void alreadyInvalidatedSessionDoesNotThrow() {
    MockHttpSession session = sessionWith(new WhoSession(identity.id(), credId, NOW));
    request.setSession(session);
    session.invalidate();
    when(whoService.resolve(any(Credential.class))).thenReturn(Optional.empty());

    SecurityContext loaded = repository.loadDeferredContext(request).get();

    assertThat(loaded.getAuthentication()).isNull();
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

  private static final class MutableClock extends Clock {
    private Instant now;

    private MutableClock(Instant now) {
      this.now = now;
    }

    private void set(Instant now) {
      this.now = now;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  @Test
  void deprecatedLoadContextReturnsWhatTheDeferredLoadDoes() {
    request.setSession(sessionWith(new WhoSession(identity.id(), credId, NOW)));
    when(whoService.resolve(any(Credential.class)))
        .thenReturn(Optional.of(new WhoPrincipal(identity, Set.of("b"))));

    SecurityContext viaHolder =
        repository.loadContext(new HttpRequestResponseHolder(request, response));
    SecurityContext deferred = repository.loadDeferredContext(request).get();

    assertThat(viaHolder.getAuthentication()).isEqualTo(deferred.getAuthentication());
    assertThat(viaHolder.getAuthentication()).isInstanceOf(WhoAuthenticationToken.class);
  }

  @Test
  void aSessionThatCannotBeInvalidatedStillLoadsAnEmptyContext() {
    HttpSession session = mock(HttpSession.class);
    when(session.getAttribute(WhoSessionSecurityContextRepository.SESSION_ATTRIBUTE))
        .thenReturn(new WhoSession(identity.id(), credId, NOW));
    doThrow(new IllegalStateException("already invalidated")).when(session).invalidate();
    MockHttpServletRequest sessionRequest =
        new MockHttpServletRequest() {
          @Override
          public HttpSession getSession(boolean create) {
            return session;
          }
        };
    when(whoService.resolve(any(Credential.class))).thenReturn(Optional.empty());

    SecurityContext loaded = repository.loadDeferredContext(sessionRequest).get();

    assertThat(loaded.getAuthentication()).isNull();
    verify(session).invalidate();
  }

  @Test
  void aTokenWithoutACredentialIdIsNotStored() {
    SecurityContext context = new SecurityContextImpl();
    context.setAuthentication(new WhoAuthenticationToken(new WhoPrincipal(identity, Set.of("a"))));

    repository.saveContext(context, request, response);

    assertThat(request.getSession(false)).isNull();
  }

  @Test
  void savingAnEmptyContextWithoutASessionCreatesNone() {
    repository.saveContext(new SecurityContextImpl(), request, response);

    assertThat(request.getSession(false)).isNull();
  }

  private MockHttpSession loadedSession() {
    MockHttpSession session = sessionWith(new WhoSession(identity.id(), credId, NOW));
    request.setSession(session);
    when(whoService.resolve(any(Credential.class)))
        .thenReturn(Optional.of(new WhoPrincipal(identity, Set.of("a"))));
    return session;
  }

  @Test
  void resavingTheLoadedTokenWithoutAStoredSessionStampsANewOne() {
    MockHttpSession session = loadedSession();
    SecurityContext loaded = repository.loadDeferredContext(request).get();
    session.removeAttribute(WhoSessionSecurityContextRepository.SESSION_ATTRIBUTE);
    clock.set(NOW.plusSeconds(30));

    repository.saveContext(loaded, request, response);

    assertThat(repository.currentSession(request))
        .contains(new WhoSession(identity.id(), credId, NOW.plusSeconds(30)));
  }

  @Test
  void resavingTheLoadedTokenAfterTheStoredIdentityChangedReStamps() {
    MockHttpSession session = loadedSession();
    SecurityContext loaded = repository.loadDeferredContext(request).get();
    session.setAttribute(
        WhoSessionSecurityContextRepository.SESSION_ATTRIBUTE,
        new WhoSession(UUID.randomUUID(), credId, NOW.minusSeconds(5)));
    clock.set(NOW.plusSeconds(30));

    repository.saveContext(loaded, request, response);

    assertThat(repository.currentSession(request))
        .contains(new WhoSession(identity.id(), credId, NOW.plusSeconds(30)));
  }

  @Test
  void resavingTheLoadedTokenAfterTheStoredCredentialChangedReStamps() {
    MockHttpSession session = loadedSession();
    SecurityContext loaded = repository.loadDeferredContext(request).get();
    session.setAttribute(
        WhoSessionSecurityContextRepository.SESSION_ATTRIBUTE,
        new WhoSession(identity.id(), UUID.randomUUID(), NOW.minusSeconds(5)));
    clock.set(NOW.plusSeconds(30));

    repository.saveContext(loaded, request, response);

    assertThat(repository.currentSession(request))
        .contains(new WhoSession(identity.id(), credId, NOW.plusSeconds(30)));
  }

  @Test
  void theCredentialPassedToTheResolverCarriesTheSessionsCredentialId() {
    request.setSession(sessionWith(new WhoSession(identity.id(), credId, NOW)));
    when(whoService.resolve(any(Credential.class))).thenReturn(Optional.empty());

    repository.loadDeferredContext(request).get();

    org.mockito.ArgumentCaptor<Credential> credential =
        org.mockito.ArgumentCaptor.forClass(Credential.class);
    verify(whoService).resolve(credential.capture());
    assertThat(credential.getValue().id()).isEqualTo(credId);
  }

  @Test
  void aSessionThatVanishesBeforeItIsInvalidatedLoadsAnEmptyContext() {
    HttpSession session = mock(HttpSession.class);
    when(session.getAttribute(WhoSessionSecurityContextRepository.SESSION_ATTRIBUTE))
        .thenReturn(new WhoSession(identity.id(), credId, NOW));
    jakarta.servlet.http.HttpServletRequest vanishing =
        mock(jakarta.servlet.http.HttpServletRequest.class);
    when(vanishing.getSession(false)).thenReturn(session, (HttpSession) null);
    when(whoService.resolve(any(Credential.class))).thenReturn(Optional.empty());

    SecurityContext loaded = repository.loadDeferredContext(vanishing).get();

    assertThat(loaded.getAuthentication()).isNull();
    verify(session, never()).invalidate();
  }

  private static final String PRINCIPAL_INDEX =
      "org.springframework.session.FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME";

  @Test
  void savingAWhoTokenSetsThePrincipalIndex() {
    repository.saveContext(contextFor(new WhoPrincipal(identity, Set.of("a"))), request, response);

    assertThat(request.getSession(false).getAttribute(PRINCIPAL_INDEX))
        .isEqualTo(identity.id().toString());
  }

  @Test
  void signOutClearsThePrincipalIndex() {
    repository.saveContext(contextFor(new WhoPrincipal(identity, Set.of("a"))), request, response);

    repository.saveContext(new SecurityContextImpl(), request, response);

    assertThat(request.getSession(false).getAttribute(PRINCIPAL_INDEX)).isNull();
    assertThat(repository.currentSession(request)).isEmpty();
  }

  @Test
  void suspendedIdentitySessionIsNotIndexed() {
    MockHttpSession session = sessionWith(new WhoSession(identity.id(), credId, NOW));
    session.setAttribute(PRINCIPAL_INDEX, identity.id().toString());
    request.setSession(session);
    when(whoService.resolve(any(Credential.class))).thenReturn(Optional.empty());

    SecurityContext loaded = repository.loadDeferredContext(request).get();
    repository.saveContext(loaded, request, response);

    assertThat(loaded.getAuthentication()).isNull();
    assertThat(session.isInvalid()).isTrue();
    assertThat(request.getSession(false)).isNull();
  }
}
