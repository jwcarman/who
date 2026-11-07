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

import java.util.Set;
import java.util.UUID;

import org.jwcarman.who.core.domain.WhoPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * who's one endpoint of its own: {@code GET /who/me} tells a browser app who is signed in.
 *
 * <p>Registered as a bean by who's autoconfiguration; it lives in its own package so an
 * application's component scan does not pick it up by accident.
 */
@RestController
public class WhoMeController {

  /**
   * The signed-in identity.
   *
   * @param identityId the identity's id
   * @param permissions the identity's current permissions
   */
  public record WhoMeResponse(UUID identityId, Set<String> permissions) {}

  /**
   * Returns the current principal, freshly resolved for this request.
   *
   * @param principal the signed-in principal, or {@code null} if there is none
   * @return {@code 200} with the identity, or {@code 401}
   */
  @GetMapping("/who/me")
  public ResponseEntity<WhoMeResponse> me(@AuthenticationPrincipal WhoPrincipal principal) {
    if (principal == null) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
    return ResponseEntity.ok(new WhoMeResponse(principal.identity().id(), principal.permissions()));
  }
}
