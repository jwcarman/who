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

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class NothingStoredAuthorizedClientRepositoryTest {

  private final NothingStoredAuthorizedClientRepository repository =
      new NothingStoredAuthorizedClientRepository();
  private final Authentication principal = new TestingAuthenticationToken("alice", null);
  private final MockHttpServletRequest request = new MockHttpServletRequest();
  private final MockHttpServletResponse response = new MockHttpServletResponse();

  @Test
  void a_saved_client_is_not_kept() {
    repository.saveAuthorizedClient(
        mock(OAuth2AuthorizedClient.class), principal, request, response);

    OAuth2AuthorizedClient loaded = repository.loadAuthorizedClient("acme", principal, request);

    assertThat(loaded).isNull();
    assertThat(request.getSession(false)).isNull();
  }

  @Test
  void removing_one_leaves_nothing_behind() {
    repository.removeAuthorizedClient("acme", principal, request, response);

    OAuth2AuthorizedClient loaded = repository.loadAuthorizedClient("acme", principal, request);

    assertThat(loaded).isNull();
    assertThat(request.getSession(false)).isNull();
  }
}
