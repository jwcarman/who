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

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class WhoMeControllerTest {

  private final WhoMeController controller = new WhoMeController();

  @Test
  void aSignedInPrincipalIsDescribed() {
    Identity identity = Identity.create();

    ResponseEntity<WhoMeController.WhoMeResponse> response =
        controller.me(new WhoPrincipal(identity, Set.of("task.read")));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody())
        .isEqualTo(new WhoMeController.WhoMeResponse(identity.id(), Set.of("task.read")));
  }

  @Test
  void noPrincipalIsUnauthorized() {
    ResponseEntity<WhoMeController.WhoMeResponse> response = controller.me(null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getBody()).isNull();
  }
}
