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

import org.jwcarman.who.core.domain.WhoPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** The website's sign-in pages: email link or code, and Keycloak. */
@Controller
class SignInController {

  /** The sign-in page; someone already signed in goes to the board. */
  @GetMapping("/signin")
  String signIn(@AuthenticationPrincipal WhoPrincipal principal) {
    return principal == null ? "signin" : "redirect:/";
  }

  /** The page {@code who.email-link.link-base-url} points at: it redeems the link's token. */
  @GetMapping("/signin/link")
  String link() {
    return "signin-link";
  }
}
