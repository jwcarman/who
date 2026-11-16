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
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.http.HttpSession;

import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.SessionRecord;
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.event.SignInMethod;
import org.jwcarman.who.core.event.WhoEvent;
import org.jwcarman.who.core.service.Resolution;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.core.service.WhoSessions;
import org.jwcarman.who.core.spi.Credential;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpRequestResponseHolder;

class WhoSessionSecurityContextRepositoryTest {

  private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");

  private final WhoService whoService = mock(WhoService.class);
  private final MutableClock clock = new MutableClock(NOW);
  private final List<WhoEvent> events = new ArrayList<>();
  private final WhoSessions sessions = InMemorySessionRecords.sessions(clock, events::add);
  private final WhoSessionSecurityContextRepository repository =
      new WhoSessionSecurityContextRepository(whoService, sessions, clock, events::add);
  private final MockHttpServletRequest request = new MockHttpServletRequest();
  private final MockHttpServletResponse response = new MockHttpServletResponse();

  private final Identity identity = Identity.create();
  private final UUID credId = UUID.randomUUID();

  private SecurityContext contextFor(WhoPrincipal principal) {
    SecurityContext context = new SecurityContextImpl();
    context.setAuthentication(new WhoAuthenticationToken(principal, credId, SignInMethod.PASSKEY));
    return context;
  }

  /** A stored who session with a live row behind it, as a sign-in leaves one. */
  private WhoSession live(Instant signedInAt) {
    return live(signedInAt, null);
  }

  private WhoSession live(Instant signedInAt, SignInMethod method) {
    SessionRecord row =
        sessions.start(identity.id(), credId, SignInMethod.PASSKEY, null, signedInAt);
    return new WhoSession(identity.id(), credId, signedInAt, method, row.id());
  }

  private static WhoSession withoutRow(WhoSession session) {
    return new WhoSession(
        session.identityId(), session.credentialId(), session.signedInAt(), session.signInMethod());
  }

  @Test
  void savingAWhoTokenStoresOnlyIds() {
    repository.saveContext(contextFor(new WhoPrincipal(identity, Set.of("a"))), request, response);

    Object stored =
        request
            .getSession(false)
            .getAttribute(WhoSessionSecurityContextRepository.SESSION_ATTRIBUTE);
    assertThat(withoutRow((WhoSession) stored))
        .isEqualTo(new WhoSession(identity.id(), credId, NOW, SignInMethod.PASSKEY));
  }

  @Test
  void loadingResolvesAFreshPrincipal() {
    request.setSession(sessionWith(live(NOW)));
    when(whoService.resolveDetailed(any(Credential.class)))
        .thenReturn(new Resolution.Resolved(new WhoPrincipal(identity, Set.of("b"))));

    SecurityContext loaded = repository.loadDeferredContext(request).get();

    assertThat(loaded.getAuthentication()).isInstanceOf(WhoAuthenticationToken.class);
    WhoAuthenticationToken token = (WhoAuthenticationToken) loaded.getAuthentication();
    assertThat(token.getAuthorities()).containsExactly(new SimpleGrantedAuthority("b"));
    assertThat(token.credentialId()).contains(credId);
  }

  @Test
  void inactiveOrUnlinkedEndsTheSession() {
    MockHttpSession session = sessionWith(live(NOW));
    request.setSession(session);
    when(whoService.resolveDetailed(any(Credential.class))).thenReturn(new Resolution.NotLinked());

    SecurityContext loaded = repository.loadDeferredContext(request).get();

    assertThat(loaded.getAuthentication()).isNull();
    assertThat(session.isInvalid()).isTrue();
  }

  @Test
  void relinkedCredentialEndsTheSession() {
    MockHttpSession session = sessionWith(live(NOW));
    request.setSession(session);
    when(whoService.resolveDetailed(any(Credential.class)))
        .thenReturn(new Resolution.Resolved(new WhoPrincipal(Identity.create(), Set.of("b"))));

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
    WhoSession original = live(earlier);
    request.setSession(sessionWith(original));
    when(whoService.resolveDetailed(any(Credential.class)))
        .thenReturn(new Resolution.Resolved(new WhoPrincipal(identity, Set.of("b"))));

    SecurityContext loaded = repository.loadDeferredContext(request).get();
    repository.saveContext(loaded, request, response);

    assertThat(repository.currentSession(request)).contains(original);
  }

  @Test
  void aNewSignInWithTheSameCredentialStampsANewSignedInAt() {
    WhoPrincipal principal = new WhoPrincipal(identity, Set.of("a"));
    repository.saveContext(contextFor(principal), request, response);
    clock.set(NOW.plusSeconds(600));

    repository.saveContext(contextFor(principal), request, response);

    assertThat(
            repository
                .currentSession(request)
                .map(WhoSessionSecurityContextRepositoryTest::withoutRow))
        .contains(
            new WhoSession(identity.id(), credId, NOW.plusSeconds(600), SignInMethod.PASSKEY));
  }

  @Test
  void savingAnEmptyContextRemovesTheSession() {
    request.setSession(sessionWith(live(NOW)));

    repository.saveContext(new SecurityContextImpl(), request, response);

    assertThat(repository.currentSession(request)).isEmpty();
  }

  @Test
  void savingANonWhoAuthenticationRemovesTheSession() {
    request.setSession(sessionWith(live(NOW)));
    SecurityContext context = new SecurityContextImpl();
    context.setAuthentication(new TestingAuthenticationToken("u", "p", "ROLE_X"));

    repository.saveContext(context, request, response);

    assertThat(repository.currentSession(request)).isEmpty();
  }

  @Test
  void isGeneratedWhenThereIsNoSessionOrItEnded() {
    assertThat(repository.loadDeferredContext(request).isGenerated()).isTrue();

    request.setSession(sessionWith(live(NOW)));
    when(whoService.resolveDetailed(any(Credential.class))).thenReturn(new Resolution.NotLinked());
    assertThat(repository.loadDeferredContext(request).isGenerated()).isTrue();

    MockHttpServletRequest valid = new MockHttpServletRequest();
    valid.setSession(sessionWith(live(NOW)));
    when(whoService.resolveDetailed(any(Credential.class)))
        .thenReturn(new Resolution.Resolved(new WhoPrincipal(identity, Set.of("b"))));
    assertThat(repository.loadDeferredContext(valid).isGenerated()).isFalse();
  }

  @Test
  void alreadyInvalidatedSessionDoesNotThrow() {
    MockHttpSession session = sessionWith(live(NOW));
    request.setSession(session);
    session.invalidate();
    when(whoService.resolveDetailed(any(Credential.class))).thenReturn(new Resolution.NotLinked());

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

  @Test
  void containsContextOnlyWhenTheSessionHoldsAWhoSession() {
    assertThat(repository.containsContext(request)).isFalse();

    request.setSession(new MockHttpSession());
    assertThat(repository.containsContext(request)).isFalse();

    request.setSession(sessionWith(live(NOW)));
    assertThat(repository.containsContext(request)).isTrue();
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
    request.setSession(sessionWith(live(NOW)));
    when(whoService.resolveDetailed(any(Credential.class)))
        .thenReturn(new Resolution.Resolved(new WhoPrincipal(identity, Set.of("b"))));

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
        .thenReturn(live(NOW));
    doThrow(new IllegalStateException("already invalidated")).when(session).invalidate();
    MockHttpServletRequest sessionRequest =
        new MockHttpServletRequest() {
          @Override
          public HttpSession getSession(boolean create) {
            return session;
          }
        };
    when(whoService.resolveDetailed(any(Credential.class))).thenReturn(new Resolution.NotLinked());

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
    MockHttpSession session = sessionWith(live(NOW));
    request.setSession(session);
    when(whoService.resolveDetailed(any(Credential.class)))
        .thenReturn(new Resolution.Resolved(new WhoPrincipal(identity, Set.of("a"))));
    return session;
  }

  @Test
  void resavingTheLoadedTokenWithoutAStoredSessionIsRefused() {
    MockHttpSession session = loadedSession();
    SecurityContext loaded = repository.loadDeferredContext(request).get();
    session.removeAttribute(WhoSessionSecurityContextRepository.SESSION_ATTRIBUTE);
    clock.set(NOW.plusSeconds(30));

    assertThatIllegalStateException()
        .isThrownBy(() -> repository.saveContext(loaded, request, response));
  }

  @Test
  void resavingTheLoadedTokenAfterTheStoredIdentityChangedIsRefused() {
    MockHttpSession session = loadedSession();
    SecurityContext loaded = repository.loadDeferredContext(request).get();
    session.setAttribute(
        WhoSessionSecurityContextRepository.SESSION_ATTRIBUTE,
        new WhoSession(UUID.randomUUID(), credId, NOW.minusSeconds(5)));
    clock.set(NOW.plusSeconds(30));

    assertThatIllegalStateException()
        .isThrownBy(() -> repository.saveContext(loaded, request, response));
  }

  @Test
  void resavingTheLoadedTokenAfterTheStoredCredentialChangedIsRefused() {
    MockHttpSession session = loadedSession();
    SecurityContext loaded = repository.loadDeferredContext(request).get();
    session.setAttribute(
        WhoSessionSecurityContextRepository.SESSION_ATTRIBUTE,
        new WhoSession(identity.id(), UUID.randomUUID(), NOW.minusSeconds(5)));
    clock.set(NOW.plusSeconds(30));

    assertThatIllegalStateException()
        .isThrownBy(() -> repository.saveContext(loaded, request, response));
  }

  @Test
  void theCredentialPassedToTheResolverCarriesTheSessionsCredentialId() {
    request.setSession(sessionWith(live(NOW)));
    when(whoService.resolveDetailed(any(Credential.class))).thenReturn(new Resolution.NotLinked());

    repository.loadDeferredContext(request).get();

    org.mockito.ArgumentCaptor<Credential> credential =
        org.mockito.ArgumentCaptor.forClass(Credential.class);
    verify(whoService).resolveDetailed(credential.capture());
    assertThat(credential.getValue().id()).isEqualTo(credId);
  }

  @Test
  void aSessionThatVanishesBeforeItIsInvalidatedLoadsAnEmptyContext() {
    HttpSession session = mock(HttpSession.class);
    when(session.getAttribute(WhoSessionSecurityContextRepository.SESSION_ATTRIBUTE))
        .thenReturn(live(NOW));
    jakarta.servlet.http.HttpServletRequest vanishing =
        mock(jakarta.servlet.http.HttpServletRequest.class);
    when(vanishing.getSession(false)).thenReturn(session, (HttpSession) null);
    when(whoService.resolveDetailed(any(Credential.class))).thenReturn(new Resolution.NotLinked());

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
    MockHttpSession session = sessionWith(live(NOW));
    session.setAttribute(PRINCIPAL_INDEX, identity.id().toString());
    request.setSession(session);
    when(whoService.resolveDetailed(any(Credential.class))).thenReturn(new Resolution.NotLinked());

    SecurityContext loaded = repository.loadDeferredContext(request).get();
    repository.saveContext(loaded, request, response);

    assertThat(loaded.getAuthentication()).isNull();
    assertThat(session.isInvalid()).isTrue();
    assertThat(request.getSession(false)).isNull();
  }

  @Test
  void a_session_remembers_how_it_signed_in() {
    SecurityContext context = new SecurityContextImpl();
    context.setAuthentication(
        new WhoAuthenticationToken(
            new WhoPrincipal(identity, Set.of("a")), credId, SignInMethod.EMAIL_CODE));

    repository.saveContext(context, request, response);

    assertThat(
            repository
                .currentSession(request)
                .map(WhoSessionSecurityContextRepositoryTest::withoutRow))
        .contains(new WhoSession(identity.id(), credId, NOW, SignInMethod.EMAIL_CODE));
  }

  @Test
  void a_session_token_carries_its_sign_in_time() {
    request.setSession(sessionWith(live(NOW, SignInMethod.EMAIL_CODE)));
    clock.set(NOW.plusSeconds(300));
    when(whoService.resolveDetailed(any(Credential.class)))
        .thenReturn(new Resolution.Resolved(new WhoPrincipal(identity, Set.of("b"))));

    WhoAuthenticationToken token =
        (WhoAuthenticationToken) repository.loadDeferredContext(request).get().getAuthentication();

    assertThat(token.signInFactor())
        .hasValueSatisfying(
            factor -> {
              assertThat(factor.getAuthority()).isEqualTo(FactorGrantedAuthority.OTT_AUTHORITY);
              assertThat(factor.getIssuedAt()).isEqualTo(NOW);
            });
    assertThat(token.getAuthorities()).contains(token.signInFactor().get());
    assertThat(token.signInMethod()).isEmpty();
  }

  @Test
  void a_session_without_a_method_has_no_factor() {
    request.setSession(sessionWith(live(NOW)));
    when(whoService.resolveDetailed(any(Credential.class)))
        .thenReturn(new Resolution.Resolved(new WhoPrincipal(identity, Set.of("b"))));

    WhoAuthenticationToken token =
        (WhoAuthenticationToken) repository.loadDeferredContext(request).get().getAuthentication();

    assertThat(token.signInFactor()).isEmpty();
    assertThat(token.getAuthorities()).containsExactly(new SimpleGrantedAuthority("b"));
  }

  @Test
  void a_sign_in_starts_a_session_row() {
    request.addHeader("User-Agent", "Mozilla/5.0 Firefox/132.0");

    repository.saveContext(contextFor(new WhoPrincipal(identity, Set.of("a"))), request, response);

    UUID sessionId = repository.currentSessionId(request).orElseThrow();
    SessionRecord row = sessions.findLive(sessionId).orElseThrow();
    assertThat(row.identityId()).isEqualTo(identity.id());
    assertThat(row.credentialId()).isEqualTo(credId);
    assertThat(row.signInMethod()).isEqualTo(SignInMethod.PASSKEY);
    assertThat(row.userAgent()).isEqualTo("Mozilla/5.0 Firefox/132.0");
  }

  @Test
  void signing_in_again_closes_the_previous_row() {
    WhoPrincipal principal = new WhoPrincipal(identity, Set.of("a"));
    repository.saveContext(contextFor(principal), request, response);
    UUID first = repository.currentSessionId(request).orElseThrow();

    repository.saveContext(contextFor(principal), request, response);

    assertThat(repository.currentSessionId(request)).isPresent().get().isNotEqualTo(first);
    assertThat(sessions.findLive(first)).isEmpty();
    assertThat(sessions.list(identity.id())).hasSize(1);
  }

  @Test
  void a_new_sign_in_without_a_method_is_refused() {
    SecurityContext context = new SecurityContextImpl();
    context.setAuthentication(
        new WhoAuthenticationToken(new WhoPrincipal(identity, Set.of("a")), credId));

    assertThatIllegalStateException()
        .isThrownBy(() -> repository.saveContext(context, request, response));
  }

  private void resolves() {
    when(whoService.resolveDetailed(any(Credential.class)))
        .thenReturn(new Resolution.Resolved(new WhoPrincipal(identity, Set.of("b"))));
  }

  @Test
  void a_session_without_a_row_id_is_signed_out() {
    MockHttpSession session = sessionWith(new WhoSession(identity.id(), credId, NOW));
    request.setSession(session);
    resolves();

    assertThat(repository.loadDeferredContext(request).get().getAuthentication()).isNull();
    assertThat(session.isInvalid()).isTrue();
    assertThat(events).isEmpty();
  }

  @Test
  void a_session_whose_row_is_gone_is_signed_out() {
    MockHttpSession session =
        sessionWith(new WhoSession(identity.id(), credId, NOW, null, UUID.randomUUID()));
    request.setSession(session);
    resolves();

    assertThat(repository.loadDeferredContext(request).get().getAuthentication()).isNull();
    assertThat(session.isInvalid()).isTrue();
  }

  @Test
  void an_ended_row_signs_the_session_out() {
    WhoSession stored = live(NOW);
    MockHttpSession session = sessionWith(stored);
    request.setSession(session);
    resolves();
    sessions.close(stored.sessionId());

    assertThat(repository.loadDeferredContext(request).get().getAuthentication()).isNull();
    assertThat(session.isInvalid()).isTrue();
    assertThat(events).isEmpty();
  }

  @Test
  void an_idle_row_signs_the_session_out() {
    request.setSession(sessionWith(live(NOW)));
    resolves();
    clock.set(NOW.plus(Duration.ofMinutes(31)));

    assertThat(repository.loadDeferredContext(request).get().getAuthentication()).isNull();
  }

  @Test
  void a_live_row_is_marked_seen() {
    WhoSession stored = live(NOW);
    request.setSession(sessionWith(stored));
    resolves();
    clock.set(NOW.plus(Duration.ofMinutes(6)));

    assertThat(repository.loadDeferredContext(request).get().getAuthentication()).isNotNull();

    assertThat(sessions.findLive(stored.sessionId()).orElseThrow().lastSeenAt())
        .isEqualTo(NOW.plus(Duration.ofMinutes(6)));
  }

  @Test
  void logout_closes_the_row() {
    repository.saveContext(contextFor(new WhoPrincipal(identity, Set.of("a"))), request, response);
    UUID sessionId = repository.currentSessionId(request).orElseThrow();

    repository.onLogout(
        request, new WhoAuthenticationToken(new WhoPrincipal(identity, Set.of("a")), credId));

    assertThat(sessions.findLive(sessionId)).isEmpty();
  }

  @Test
  void current_session_id_is_empty_without_a_who_session() {
    assertThat(repository.currentSessionId(request)).isEmpty();
    request.setSession(sessionWith(new WhoSession(identity.id(), credId, NOW)));
    assertThat(repository.currentSessionId(request)).isEmpty();
  }
}
