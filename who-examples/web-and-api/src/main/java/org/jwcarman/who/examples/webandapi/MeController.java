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
package org.jwcarman.who.examples.webandapi;

import java.util.Set;
import java.util.UUID;

import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.event.SignInMethod;
import org.jwcarman.who.spring.security.WhoAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Who is calling, and how. Signed out, who's chain answers {@code 401} before this runs. */
@RestController
class MeController {

  /**
   * The caller.
   *
   * @param identityId their identity
   * @param permissions what this call may do: a token's are the person's, a key's its scopes
   * @param signedInWith {@code token} or {@code api-key}
   */
  record Me(UUID identityId, Set<String> permissions, String signedInWith) {}

  @GetMapping("/api/me")
  Me me(@AuthenticationPrincipal WhoPrincipal principal, Authentication authentication) {
    return new Me(principal.identity().id(), principal.permissions(), signedInWith(authentication));
  }

  /** How the call signed in, as who recorded it. */
  static String signedInWith(Authentication authentication) {
    return authentication instanceof WhoAuthenticationToken token
            && token.signInMethod().filter(SignInMethod.API_KEY::equals).isPresent()
        ? "api-key"
        : "token";
  }
}
