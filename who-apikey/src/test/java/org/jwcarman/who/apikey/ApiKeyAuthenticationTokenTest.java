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
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ApiKeyAuthenticationTokenTest {

  private static final String KEY = "who_secret-key-value";

  @Nested
  class Creation {

    @Test
    void holds_the_key_as_credentials_and_is_not_authenticated() {
      ApiKeyAuthenticationToken token = new ApiKeyAuthenticationToken(KEY);

      assertThat(token.getCredentials()).isEqualTo(KEY);
      assertThat(token.getPrincipal()).isNull();
      assertThat(token.isAuthenticated()).isFalse();
    }

    @Test
    void rejects_a_null_key() {
      assertThatNullPointerException()
          .isThrownBy(() -> new ApiKeyAuthenticationToken(null))
          .withMessage("key must not be null");
    }
  }

  @Nested
  class Erasing {

    @Test
    void forgets_the_key() {
      ApiKeyAuthenticationToken token = new ApiKeyAuthenticationToken(KEY);

      token.eraseCredentials();

      assertThat(token.getCredentials()).isNull();
    }

    @Test
    void the_key_never_shows_in_to_string() {
      assertThat(new ApiKeyAuthenticationToken(KEY)).asString().doesNotContain(KEY);
    }
  }

  @Nested
  class Equality {

    @Test
    void tokens_with_the_same_key_are_equal() {
      ApiKeyAuthenticationToken token = new ApiKeyAuthenticationToken(KEY);
      ApiKeyAuthenticationToken same = new ApiKeyAuthenticationToken(KEY);

      assertThat(token).isEqualTo(same).hasSameHashCodeAs(same);
    }

    @Test
    void tokens_with_different_keys_are_not_equal() {
      assertThat(new ApiKeyAuthenticationToken(KEY))
          .isNotEqualTo(new ApiKeyAuthenticationToken(KEY + "-other"));
    }

    @Test
    void an_erased_token_is_not_equal_to_one_that_still_holds_its_key() {
      ApiKeyAuthenticationToken erased = new ApiKeyAuthenticationToken(KEY);
      erased.eraseCredentials();

      assertThat(erased).isNotEqualTo(new ApiKeyAuthenticationToken(KEY));
    }

    @Test
    void a_token_is_not_equal_to_another_type() {
      assertThat(new ApiKeyAuthenticationToken(KEY)).isNotEqualTo(KEY);
    }
  }
}
