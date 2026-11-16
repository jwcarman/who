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
import static org.mockito.Mockito.mock;

import java.util.List;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.spring.security.WhoPaths;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.security.web.webauthn.authentication.PublicKeyCredentialRequestOptionsFilter;
import org.springframework.security.web.webauthn.authentication.WebAuthnAuthenticationFilter;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;
import org.springframework.security.web.webauthn.registration.PublicKeyCredentialCreationOptionsFilter;
import org.springframework.security.web.webauthn.registration.WebAuthnRegistrationFilter;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class PasskeyFilterPathsTest {

  private static final WhoPaths PATHS = new WhoPaths("/auth");

  private final WebAuthnRelyingPartyOperations rp = mock(WebAuthnRelyingPartyOperations.class);
  private final WebAuthnAuthenticationFilter signIn = new WebAuthnAuthenticationFilter();
  private final PublicKeyCredentialRequestOptionsFilter signInOptions =
      new PublicKeyCredentialRequestOptionsFilter(rp);
  private final PublicKeyCredentialCreationOptionsFilter registrationOptions =
      new PublicKeyCredentialCreationOptionsFilter(rp);
  private final WebAuthnRegistrationFilter registration =
      new WebAuthnRegistrationFilter(mock(UserCredentialRepository.class), rp);
  private final PasskeyFilterPaths filterPaths = new PasskeyFilterPaths();

  /**
   * Whether the filter takes the request for itself rather than passing it on. A filter that takes
   * it may fail on the empty request; that still means it took it.
   */
  private static boolean takes(Filter filter, String method, String path) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest(method, path);
    MockFilterChain chain = new MockFilterChain();
    try {
      filter.doFilter(request, new MockHttpServletResponse(), chain);
    } catch (RuntimeException e) {
      return true;
    }
    return chain.getRequest() == null;
  }

  private DefaultSecurityFilterChain chain(Filter... filters) {
    return new DefaultSecurityFilterChain(AnyRequestMatcher.INSTANCE, List.of(filters));
  }

  @Test
  void a_marked_chain_gets_who_paths() throws Exception {
    filterPaths.mark(signIn, PATHS);

    filterPaths.postProcessAfterInitialization(
        chain(signIn, signInOptions, registrationOptions, registration), "chain");

    assertThat(takes(signIn, "POST", "/auth/passkey/sign-in")).isTrue();
    assertThat(takes(signInOptions, "POST", "/auth/passkey/sign-in/options")).isTrue();
    assertThat(takes(registrationOptions, "POST", "/auth/passkey/register/options")).isTrue();
    assertThat(takes(registration, "POST", "/auth/passkey/register")).isTrue();
    assertThat(takes(registration, "DELETE", "/auth/passkey/register/abc")).isTrue();

    assertThat(takes(signIn, "POST", "/login/webauthn")).isFalse();
    assertThat(takes(signInOptions, "POST", "/webauthn/authenticate/options")).isFalse();
    assertThat(takes(registrationOptions, "POST", "/webauthn/register/options")).isFalse();
    assertThat(takes(registration, "POST", "/webauthn/register")).isFalse();
    assertThat(takes(registration, "DELETE", "/webauthn/register/abc")).isFalse();
  }

  @Test
  void an_unmarked_chain_is_untouched() throws Exception {
    filterPaths.postProcessAfterInitialization(
        chain(signIn, signInOptions, registrationOptions, registration), "app");

    assertThat(takes(signIn, "POST", "/login/webauthn")).isTrue();
    assertThat(takes(signInOptions, "POST", "/webauthn/authenticate/options")).isTrue();
    assertThat(takes(registrationOptions, "POST", "/webauthn/register/options")).isTrue();
    assertThat(takes(registration, "POST", "/webauthn/register")).isTrue();
    assertThat(takes(registration, "DELETE", "/webauthn/register/abc")).isTrue();

    assertThat(takes(signInOptions, "POST", "/auth/passkey/sign-in/options")).isFalse();
  }

  @Test
  void a_marked_chain_missing_a_filter_fails() {
    filterPaths.mark(signIn, PATHS);
    DefaultSecurityFilterChain incomplete = chain(signIn, signInOptions, registrationOptions);

    assertThatThrownBy(() -> filterPaths.postProcessAfterInitialization(incomplete, "chain"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("WebAuthnRegistrationFilter")
        .hasMessageContaining("webAuthn()");
  }

  @Test
  void other_beans_pass_through() {
    Object bean = new Object();

    assertThat(filterPaths.postProcessAfterInitialization(bean, "bean")).isSameAs(bean);
  }
}
