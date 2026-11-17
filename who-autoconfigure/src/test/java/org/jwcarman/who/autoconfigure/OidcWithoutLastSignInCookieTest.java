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
package org.jwcarman.who.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.Map;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.signup.SignUpDecision;
import org.jwcarman.who.spring.security.web.LastSignInCookie;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

/** Provider sign-in and sign-up with {@code who.security.last-sign-in-cookie.enabled=false}. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@TestPropertySource(properties = "who.security.last-sign-in-cookie.enabled=false")
class OidcWithoutLastSignInCookieTest extends AbstractOidcTest {

  @Test
  void a_provider_sign_in_sets_no_cookie() throws Exception {
    MvcResult result = signInAsAlice();

    assertThat(result.getResponse().getRedirectedUrl()).isEqualTo(SUCCESS_URL);
    assertThat(result.getResponse().getCookie(LastSignInCookie.NAME)).isNull();
  }

  @Test
  void a_completed_sign_up_sets_no_cookie() throws Exception {
    policy.set(request -> SignUpDecision.allow());
    signInAtProvider("carol", Map.of());

    MvcResult result = browser.perform(post("/who/oidc/pending/sign-up")).andReturn();

    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    assertThat(result.getResponse().getCookie(LastSignInCookie.NAME)).isNull();
  }
}
