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
package org.jwcarman.who.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.IdentityStatus;
import org.jwcarman.who.core.event.SignInFailed;
import org.jwcarman.who.core.event.SignInFailureReason;
import org.jwcarman.who.core.event.SignInMethod;
import org.jwcarman.who.core.event.WhoEvent;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.spring.security.web.SignInFailedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The API key filter in a chain that adds it with {@link WhoApiKeySecurity#apiKey()}: a valid key
 * authenticates the request and the chain continues; a key that does not sign in is refused with
 * {@code 401} at once, even where anonymous access is permitted.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ApiKeyAuthenticationFilterTest extends AbstractApiKeySignInTest {

  private static final String OPEN = "/open/whoami";
  private static final String SECURE = "/secure/whoami";

  @Autowired private ApiKeyService apiKeyService;
  @Autowired private ApiKeyCredentialRepository apiKeyCredentialRepository;
  @Autowired private IdentityRepository identityRepository;

  private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
    return mvc.perform(request).andReturn();
  }

  private String keyFor(Identity identity) {
    return apiKeyService.create(identityRepository.save(identity), "filter");
  }

  private UUID credentialIdOf(String key) {
    return apiKeyCredentialRepository
        .findByKeyHash(ApiKeyService.sha256Hex(key))
        .orElseThrow()
        .id();
  }

  /** Asserts the uniform refusal: {@code 401}, handed to the application as a failed sign-in. */
  private void assertRefused(MvcResult result) throws Exception {
    assertThat(result.getResponse().getStatus()).isEqualTo(401);
    assertThat(result.getResponse().getContentAsString()).isEmpty();
    assertThat(HANDED_OFF)
        .singleElement()
        .isInstanceOf(SignInFailedException.class)
        .extracting(Throwable::getMessage)
        .isEqualTo("Sign-in failed");
  }

  @Nested
  class With_a_valid_key {

    @Test
    void a_valid_key_authenticates_and_continues_the_chain() throws Exception {
      Identity identity = Identity.create();
      String key = keyFor(identity);

      MvcResult result = perform(get(SECURE).header(HEADER, key));

      assertThat(result.getResponse().getStatus()).isEqualTo(200);
      assertThat(result.getResponse().getRedirectedUrl()).isNull();
      assertThat(result.getResponse().getContentAsString()).isEqualTo(identity.id().toString());
    }

    @Test
    void a_valid_key_creates_no_session() throws Exception {
      String key = keyFor(Identity.create());

      MvcResult result = perform(get(SECURE).header(HEADER, key));

      assertThat(result.getResponse().getStatus()).isEqualTo(200);
      assertThat(result.getRequest().getSession(false)).isNull();
    }

    @Test
    void a_valid_key_does_not_sign_in_the_session_the_request_has() throws Exception {
      String key = keyFor(Identity.create());
      MockHttpSession session = new MockHttpSession();

      perform(get(SECURE).session(session).header(HEADER, key));
      MvcResult result = perform(get(OPEN).session(session));

      assertThat(result.getResponse().getContentAsString()).isEqualTo(ANONYMOUS);
      assertThat(
              session.getAttribute(
                  HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
          .isNull();
    }

    @Test
    void the_next_request_without_the_header_is_anonymous_again() throws Exception {
      String key = keyFor(Identity.create());
      perform(get(SECURE).header(HEADER, key));

      MvcResult result = perform(get(OPEN));

      assertThat(result.getResponse().getContentAsString()).isEqualTo(ANONYMOUS);
    }
  }

  @Nested
  class With_a_key_that_does_not_sign_in {

    @Test
    void an_unknown_key_is_401_even_where_anonymous_is_permitted() throws Exception {
      assertRefused(perform(get(OPEN).header(HEADER, "who_unknown")));
    }

    @Test
    void a_revoked_key_is_401_even_where_anonymous_is_permitted() throws Exception {
      String key = keyFor(Identity.create());
      apiKeyService.revoke(credentialIdOf(key));

      assertRefused(perform(get(OPEN).header(HEADER, key)));
    }

    @Test
    void a_key_of_an_inactive_identity_is_401_with_inactive_identity() throws Exception {
      Identity suspended = Identity.create(IdentityStatus.SUSPENDED);
      String key = keyFor(suspended);

      assertRefused(perform(get(OPEN).header(HEADER, key)));
      assertThat(EVENTS)
          .containsExactly(
              new SignInFailed(
                  NOW,
                  SignInMethod.API_KEY,
                  SignInFailureReason.INACTIVE_IDENTITY,
                  null,
                  suspended.id(),
                  credentialIdOf(key)));
    }

    @Test
    void an_unknown_key_and_an_inactive_identity_get_the_same_answer() throws Exception {
      MockHttpServletResponse unknown =
          perform(get(OPEN).header(HEADER, "who_unknown")).getResponse();
      MockHttpServletResponse inactive =
          perform(get(OPEN).header(HEADER, keyFor(Identity.create(IdentityStatus.DISABLED))))
              .getResponse();

      assertThat(inactive.getStatus()).isEqualTo(unknown.getStatus()).isEqualTo(401);
      assertThat(inactive.getErrorMessage()).isEqualTo(unknown.getErrorMessage());
      assertThat(inactive.getContentAsString()).isEqualTo(unknown.getContentAsString());
      assertThat(inactive.getHeaderNames()).isEqualTo(unknown.getHeaderNames());
    }
  }

  @Nested
  class Without_a_key {

    @Test
    void no_header_reaches_a_permitted_endpoint_anonymously() throws Exception {
      MvcResult result = perform(get(OPEN));

      assertThat(result.getResponse().getStatus()).isEqualTo(200);
      assertThat(result.getResponse().getContentAsString()).isEqualTo(ANONYMOUS);
      assertThat(EVENTS).isEmpty();
    }

    @Test
    void a_blank_header_reaches_a_permitted_endpoint_anonymously() throws Exception {
      MvcResult result = perform(get(OPEN).header(HEADER, "   "));

      assertThat(result.getResponse().getStatus()).isEqualTo(200);
      assertThat(result.getResponse().getContentAsString()).isEqualTo(ANONYMOUS);
      assertThat(EVENTS).isEmpty();
    }
  }

  @Nested
  class When_already_signed_in {

    @Test
    void a_session_that_is_already_signed_in_ignores_the_header() throws Exception {
      MockHttpSession session = new MockHttpSession();
      session.setAttribute(
          HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
          new SecurityContextImpl(new TestingAuthenticationToken("alice", "n/a", "ROLE_X")));

      MvcResult result = perform(get(SECURE).session(session).header(HEADER, "who_unknown"));

      assertThat(result.getResponse().getStatus()).isEqualTo(200);
      assertThat(result.getResponse().getContentAsString()).isEqualTo("alice");
      assertThat(EVENTS).isEmpty();
      assertThat(HANDED_OFF).isEmpty();
    }
  }

  /** The filter on its own, made with the constructor that predates the provider. */
  @Nested
  class Made_with_the_repository_and_service {

    private final ApiKeyCredentialRepository repository = mock(ApiKeyCredentialRepository.class);
    private final WhoService whoService = mock(WhoService.class);
    private final ApiKeyAuthenticationFilter filter =
        new ApiKeyAuthenticationFilter(repository, whoService, HEADER);

    @AfterEach
    void clearContext() {
      SecurityContextHolder.clearContext();
    }

    @Test
    void an_unknown_key_is_401_and_the_chain_stops() throws Exception {
      when(repository.findByKeyHash(any())).thenReturn(Optional.empty());
      MockHttpServletRequest request = new MockHttpServletRequest();
      request.addHeader(HEADER, "who_unknown");
      MockHttpServletResponse response = new MockHttpServletResponse();
      MockFilterChain chain = new MockFilterChain();

      filter.doFilter(request, response, chain);

      assertThat(response.getStatus()).isEqualTo(401);
      assertThat(chain.getRequest()).isNull();
      assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void a_fault_looking_up_the_key_propagates_and_authenticates_no_one() {
      when(repository.findByKeyHash(any())).thenThrow(new IllegalStateException("db down"));
      MockHttpServletRequest request = new MockHttpServletRequest();
      request.addHeader(HEADER, "who_any");
      MockFilterChain chain = new MockFilterChain();

      assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), chain))
          .isInstanceOf(IllegalStateException.class);
      assertThat(chain.getRequest()).isNull();
      assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void null_collaborators_are_refused() {
      assertThatThrownBy(() -> new ApiKeyAuthenticationFilter(null, whoService, HEADER))
          .isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> new ApiKeyAuthenticationFilter(repository, null, HEADER))
          .isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> new ApiKeyAuthenticationFilter(repository, whoService, null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  class The_request_token {

    private static final String KEY = "who_secret";

    @Test
    void holds_the_key_until_its_credentials_are_erased() {
      ApiKeyAuthenticationToken token = new ApiKeyAuthenticationToken(KEY);

      assertThat(token.getCredentials()).isEqualTo(KEY);
      assertThat(token.isAuthenticated()).isFalse();
      assertThat(token.getPrincipal()).isNull();

      token.eraseCredentials();

      assertThat(token.getCredentials()).isNull();
    }

    @Test
    void never_shows_the_key() {
      assertThat(new ApiKeyAuthenticationToken(KEY)).asString().doesNotContain(KEY);
    }

    @Test
    void once_erased_signs_in_no_one_and_reports_nothing() {
      ApiKeyCredentialRepository repository = mock(ApiKeyCredentialRepository.class);
      List<WhoEvent> events = new ArrayList<>();
      ApiKeyAuthenticationProvider provider =
          new ApiKeyAuthenticationProvider(
              repository, mock(WhoService.class), events::add, Clock.systemUTC());
      ApiKeyAuthenticationToken token = new ApiKeyAuthenticationToken(KEY);
      token.eraseCredentials();

      assertThatThrownBy(() -> provider.authenticate(token))
          .isInstanceOf(BadCredentialsException.class);
      verifyNoInteractions(repository);
      assertThat(events).isEmpty();
    }
  }
}
