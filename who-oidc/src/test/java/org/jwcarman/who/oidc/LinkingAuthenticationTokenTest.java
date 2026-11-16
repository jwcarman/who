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

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class LinkingAuthenticationTokenTest {

  private static final OAuth2User USER =
      new DefaultOAuth2User(AuthorityUtils.NO_AUTHORITIES, Map.of("id", 7), "id");
  private static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");
  private static final LinkIntent INTENT = new LinkIntent(UUID.randomUUID(), NOW);

  private static LinkingAuthenticationToken token(LinkIntent intent) {
    return new LinkingAuthenticationToken(USER, AuthorityUtils.NO_AUTHORITIES, "acme", intent);
  }

  @Test
  void tokens_for_the_same_sign_in_and_intent_are_equal() {
    assertThat(token(INTENT)).isEqualTo(token(INTENT)).hasSameHashCodeAs(token(INTENT));
  }

  @Test
  void a_different_intent_makes_a_different_token() {
    assertThat(token(INTENT)).isNotEqualTo(token(new LinkIntent(UUID.randomUUID(), NOW)));
  }

  @Test
  void the_same_intent_for_another_account_is_a_different_token() {
    OAuth2User other = new DefaultOAuth2User(AuthorityUtils.NO_AUTHORITIES, Map.of("id", 8), "id");

    assertThat(token(INTENT))
        .isNotEqualTo(
            new LinkingAuthenticationToken(other, AuthorityUtils.NO_AUTHORITIES, "acme", INTENT));
  }

  @Test
  void a_plain_sign_in_token_is_not_a_linking_one() {
    assertThat(token(INTENT))
        .isNotEqualTo(new OAuth2AuthenticationToken(USER, AuthorityUtils.NO_AUTHORITIES, "acme"));
  }
}
