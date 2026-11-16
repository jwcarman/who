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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Duration;
import java.util.Set;

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
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.jwcarman.who.spring.security.web.WhoExceptionHandoff;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class PendingSignUpFilterTest {

  private final IdentityRepository identities = mock(IdentityRepository.class);
  private final OidcSignUpService signUpService = mock(OidcSignUpService.class);
  private final PendingSignUpFilter filter =
      new PendingSignUpFilter(
          signUpService,
          identities,
          new WhoSessionSecurityContextRepository(mock(WhoService.class), Clock.systemUTC()),
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
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", PendingSignUpFilter.LINK_URL);
    request.setServletPath(PendingSignUpFilter.LINK_URL);
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertThat(response.getStatus()).isEqualTo(401);
    verifyNoInteractions(identities, signUpService);
  }
}
