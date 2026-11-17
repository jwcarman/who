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
package org.jwcarman.who.spring.security.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.event.SignInMethod;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.core.service.LastCredentialGuard;
import org.jwcarman.who.core.service.SignInCredentialCounter;
import org.jwcarman.who.spring.security.RecentSignInAuthorizationManager;
import org.jwcarman.who.spring.security.WhoAuthenticationToken;
import org.jwcarman.who.spring.security.WhoCaller;
import org.jwcarman.who.spring.security.WhoSession;
import org.jwcarman.who.spring.security.WhoSessionSecurityContextRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.PlatformTransactionManager;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class WhoMeControllerTest {

  private static final Instant SIGNED_IN = Instant.parse("2026-11-17T10:00:00Z");
  private static final Duration WINDOW = Duration.ofMinutes(10);

  private final Identity identity = Identity.create();
  private final WhoPrincipal principal = new WhoPrincipal(identity, Set.of("task.read"));
  private final MockHttpServletRequest request = new MockHttpServletRequest();
  private final WhoSessionSecurityContextRepository repository =
      mock(WhoSessionSecurityContextRepository.class);
  private final RecentSignInAuthorizationManager recentSignIn =
      new RecentSignInAuthorizationManager(WINDOW, Clock.systemUTC());

  private static SignInCredentialCounter counter(String kind, long count) {
    return new SignInCredentialCounter() {
      @Override
      public String kind() {
        return kind;
      }

      @Override
      public long count(UUID identityId) {
        return count;
      }
    };
  }

  private WhoMeController controller(LastCredentialGuard guard) {
    StaticListableBeanFactory beans = new StaticListableBeanFactory();
    if (guard != null) {
      beans.addBean("guard", guard);
    }
    ObjectProvider<LastCredentialGuard> provider = beans.getBeanProvider(LastCredentialGuard.class);
    return new WhoMeController(repository, recentSignIn, provider);
  }

  private WhoMeController.WhoMeResponse me(
      WhoMeController controller, WhoAuthenticationToken token) {
    return controller.resolve(request, token);
  }

  @Nested
  class Me {

    @Test
    void me_reports_the_sign_in_method_and_recent_window() {
      WhoAuthenticationToken token = new WhoAuthenticationToken(principal, UUID.randomUUID());
      when(repository.caller(request, token))
          .thenReturn(
              new WhoCaller.Session(
                  token,
                  new WhoSession(
                      identity.id(),
                      UUID.randomUUID(),
                      SIGNED_IN,
                      SignInMethod.PASSKEY,
                      UUID.randomUUID())));

      WhoMeController.WhoMeResponse body = me(controller(null), token);

      assertThat(body.identityId()).isEqualTo(identity.id());
      assertThat(body.permissions()).containsExactly("task.read");
      assertThat(body.signInMethod()).isEqualTo("passkey");
      assertThat(body.recentSignInUntil()).isEqualTo(SIGNED_IN.plus(WINDOW));
    }

    @Test
    void me_reports_the_method_of_a_stateless_request_and_no_recent_window() {
      WhoAuthenticationToken token =
          new WhoAuthenticationToken(principal, UUID.randomUUID(), SignInMethod.API_KEY);
      when(repository.caller(request, token))
          .thenReturn(new WhoCaller.Stateless(token, SignInMethod.API_KEY));

      WhoMeController.WhoMeResponse body = me(controller(null), token);

      assertThat(body.signInMethod()).isEqualTo("api-key");
      assertThat(body.recentSignInUntil()).isNull();
    }

    @Test
    void me_reports_no_method_when_nothing_says_how_the_caller_signed_in() {
      WhoAuthenticationToken token = new WhoAuthenticationToken(principal);
      when(repository.caller(request, token)).thenReturn(new WhoCaller.Other(token));

      WhoMeController.WhoMeResponse body = me(controller(null), token);

      assertThat(body.signInMethod()).isNull();
      assertThat(body.recentSignInUntil()).isNull();
    }

    @Test
    void me_reports_credential_counts() {
      LastCredentialGuard guard =
          new LastCredentialGuard(
              mock(IdentityRepository.class),
              List.of(counter("email", 2), counter("passkey", 1), counter("account", 0)),
              mock(PlatformTransactionManager.class));
      Map<String, Long> counts = Map.of("email", 2L, "passkey", 1L, "account", 0L);
      WhoAuthenticationToken token = new WhoAuthenticationToken(principal);
      when(repository.caller(request, token)).thenReturn(new WhoCaller.Other(token));

      assertThat(me(controller(guard), token).credentials()).isEqualTo(counts);
    }

    @Test
    void me_reports_no_credential_counts_without_a_guard() {
      WhoAuthenticationToken token = new WhoAuthenticationToken(principal);
      when(repository.caller(request, token)).thenReturn(new WhoCaller.Other(token));

      assertThat(me(controller(null), token).credentials()).isEmpty();
    }

    @Test
    void signed_out_throws_not_signed_in() {
      WhoMeController controller = controller(null);

      assertThatThrownBy(() -> controller.resolve(request, null))
          .isInstanceOf(NotSignedInException.class);
    }

    @Test
    void another_kind_of_authentication_is_not_a_who_sign_in() {
      WhoMeController controller = controller(null);
      Authentication other = UsernamePasswordAuthenticationToken.authenticated("x", "y", List.of());

      assertThatThrownBy(() -> controller.resolve(request, other))
          .isInstanceOf(NotSignedInException.class);
    }
  }
}
