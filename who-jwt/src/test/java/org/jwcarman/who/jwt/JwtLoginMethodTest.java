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
package org.jwcarman.who.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.federated.FederatedCredentialRepository;
import org.springframework.security.oauth2.jwt.JwtDecoder;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class JwtLoginMethodTest {

  @Test
  void the_challenge_is_bearer() {
    JwtLoginMethod method =
        new JwtLoginMethod(
            new WhoJwtAuthenticationConverter(
                mock(FederatedCredentialRepository.class), mock(WhoService.class)),
            mock(JwtDecoder.class));

    assertThat(method.challenge()).contains("Bearer");
  }
}
