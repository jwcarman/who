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
package org.jwcarman.who.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.IdentityStatus;
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.event.SignInMethod;
import org.jwcarman.who.core.event.WhoEventPublisher;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.spring.security.WhoAuthenticationToken;
import org.jwcarman.who.spring.security.WhoPaths;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.jwcarman.who.spring.security.web.WhoExceptionHandoff;
import org.jwcarman.who.test.InMemorySessionRecordRepository;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class PendingSignUpFilterTest {

  private final IdentityRepository identities = mock(IdentityRepository.class);
  private final OidcSignUpService signUpService = mock(OidcSignUpService.class);
  private final PendingSignUpFilter filter =
      new PendingSignUpFilter(
          WhoPaths.defaults(),
          signUpService,
          identities,
          whoSessionRepository(mock(WhoService.class), Clock.systemUTC()),
          mock(SessionAuthenticationStrategy.class),
          new PendingSignUps(),
          new WhoExceptionHandoff(null),
          WhoEventPublisher.NOOP,
          null,
          Clock.systemUTC(),
          Duration.ofMinutes(10));

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void confirming_a_link_signed_in_without_a_browser_session_is_401_and_links_nothing()
      throws Exception {
    Identity bob = Identity.create(IdentityStatus.ACTIVE);
    SecurityContextHolder.getContext()
        .setAuthentication(
            new WhoAuthenticationToken(
                new WhoPrincipal(bob, Set.of()), bob.id(), SignInMethod.API_KEY));
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/who/oidc/pending/link");
    request.setServletPath("/who/oidc/pending/link");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertThat(response.getStatus()).isEqualTo(401);
    verifyNoInteractions(identities, signUpService);
  }

  @Test
  void old_path_is_not_served() throws Exception {
    for (String old : new String[] {"/who/oidc/sign-up", "/who/oidc/link"}) {
      MockHttpServletRequest request = new MockHttpServletRequest("POST", old);
      request.setServletPath(old);
      MockFilterChain chain = new MockFilterChain();

      filter.doFilter(request, new MockHttpServletResponse(), chain);

      assertThat(chain.getRequest()).isSameAs(request);
    }
    verifyNoInteractions(identities, signUpService);
  }

  @Test
  void a_sign_up_signs_in_stamped_with_its_time() throws Exception {
    Instant now = Instant.parse("2026-11-16T10:00:00Z");
    SessionAuthenticationStrategy strategy = mock(SessionAuthenticationStrategy.class);
    PendingSignUps pendingSignUps = new PendingSignUps();
    PendingSignUpFilter fixed =
        new PendingSignUpFilter(
            WhoPaths.defaults(),
            signUpService,
            identities,
            whoSessionRepository(mock(WhoService.class), Clock.fixed(now, ZoneOffset.UTC)),
            strategy,
            pendingSignUps,
            new WhoExceptionHandoff(null),
            WhoEventPublisher.NOOP,
            null,
            Clock.fixed(now, ZoneOffset.UTC),
            Duration.ofMinutes(10));
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/who/oidc/pending/sign-up");
    request.setServletPath("/who/oidc/pending/sign-up");
    pendingSignUps.save(
        request,
        new PendingSignUp(
            "google",
            "https://accounts.google.com",
            "alice-sub",
            "alice@example.com",
            "alice@example.com",
            Map.of(),
            null,
            now.plusSeconds(600)));
    Identity alice = Identity.create(IdentityStatus.ACTIVE);
    when(signUpService.signUp(any()))
        .thenReturn(
            new OidcSignUpService.SignedUp(new WhoPrincipal(alice, Set.of()), UUID.randomUUID()));

    fixed.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

    ArgumentCaptor<Authentication> signedIn = ArgumentCaptor.forClass(Authentication.class);
    verify(strategy).onAuthentication(signedIn.capture(), any(), any());
    WhoAuthenticationToken who = (WhoAuthenticationToken) signedIn.getValue();
    assertThat(who.signInFactor())
        .hasValueSatisfying(
            factor -> {
              assertThat(factor.getAuthority())
                  .isEqualTo(FactorGrantedAuthority.AUTHORIZATION_CODE_AUTHORITY);
              assertThat(factor.getIssuedAt()).isEqualTo(now);
            });
  }

  private static WhoSessionSecurityContextRepository whoSessionRepository(
      WhoService whoService, Clock clock) {
    return new WhoSessionSecurityContextRepository(
        whoService,
        InMemorySessionRecordRepository.whoSessions(clock, WhoEventPublisher.NOOP),
        clock,
        WhoEventPublisher.NOOP,
        _ -> false);
  }
}
