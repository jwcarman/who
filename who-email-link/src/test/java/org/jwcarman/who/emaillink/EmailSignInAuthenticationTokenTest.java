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
package org.jwcarman.who.emaillink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.emaillink.EmailSignInProof.EmailCode;
import org.jwcarman.who.emaillink.EmailSignInProof.LinkToken;
import org.jwcarman.who.emaillink.EmailSignInProof.Malformed;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class EmailSignInAuthenticationTokenTest {

  @Nested
  class Accessors {

    @Test
    void the_proof_is_the_credentials() {
      EmailCode proof = new EmailCode("a@b.c", "K7F2QM");
      EmailSignInAuthenticationToken token = new EmailSignInAuthenticationToken(proof);

      assertThat(token.proof()).isEqualTo(proof);
      assertThat(token.getCredentials()).isEqualTo(proof);
      assertThat(token.isAuthenticated()).isFalse();
      assertThat(token.getAuthorities()).isEmpty();
    }

    @Test
    void the_principal_is_the_address_of_a_code() {
      assertThat(
              new EmailSignInAuthenticationToken(new EmailCode("a@b.c", "K7F2QM")).getPrincipal())
          .isEqualTo("a@b.c");
    }

    @Test
    void a_link_or_malformed_proof_has_no_principal() {
      assertThat(new EmailSignInAuthenticationToken(new LinkToken("t")).getPrincipal()).isNull();
      assertThat(new EmailSignInAuthenticationToken(new Malformed()).getPrincipal()).isNull();
    }

    @Test
    void a_null_proof_is_rejected() {
      assertThatThrownBy(() -> new EmailSignInAuthenticationToken(null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  class Equality {

    @Test
    void tokens_with_equal_proofs_are_equal() {
      EmailSignInAuthenticationToken a = new EmailSignInAuthenticationToken(new LinkToken("t"));
      EmailSignInAuthenticationToken b = new EmailSignInAuthenticationToken(new LinkToken("t"));

      assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    }

    @Test
    void tokens_with_different_proofs_are_not_equal() {
      EmailSignInAuthenticationToken a = new EmailSignInAuthenticationToken(new LinkToken("t"));

      assertThat(a)
          .isNotEqualTo(new EmailSignInAuthenticationToken(new LinkToken("u")))
          .isNotEqualTo(new EmailSignInAuthenticationToken(new Malformed()))
          .isNotEqualTo("t");
    }
  }

  @Nested
  class Secrecy {

    @Test
    void the_proofs_never_print_their_secrets() {
      assertThat(new EmailCode("a@b.c", "K7F2QM").toString())
          .contains("a@b.c")
          .doesNotContain("K7F2QM");
      assertThat(new LinkToken("secret-token").toString()).doesNotContain("secret-token");
      assertThat(new EmailSignInAuthenticationToken(new EmailCode("a@b.c", "K7F2QM")).toString())
          .doesNotContain("K7F2QM");
    }
  }
}
