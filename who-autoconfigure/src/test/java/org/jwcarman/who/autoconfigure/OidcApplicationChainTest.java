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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.Map;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Provider sign-in in a chain the application builds with {@code WhoWebSecurity.passwordless()}.
 */
@TestPropertySource(properties = "test.application-chain=true")
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class OidcApplicationChainTest extends AbstractOidcTest {

  @Test
  void works_in_an_application_chain() throws Exception {
    assertThat(context.getBeansOfType(SecurityFilterChain.class))
        .containsOnlyKeys("applicationChain");

    MvcResult signedIn = signInAsAlice();
    assertThat(signedIn.getResponse().getRedirectedUrl()).isEqualTo(SUCCESS_URL);
    assertThat(me()).isEqualTo(200);
  }

  @Test
  void pending_sign_up_endpoints_work_in_an_application_chain() throws Exception {
    MvcResult callback = signInAtProvider("carol", Map.of());
    assertThat(callback.getResponse().getRedirectedUrl())
        .isEqualTo(FAILURE_URL + "?pending=sign-up");

    MvcResult pending = browser.perform(get("/who/oidc/pending")).andReturn();

    assertThat(pending.getResponse().getStatus()).isEqualTo(200);
  }
}
