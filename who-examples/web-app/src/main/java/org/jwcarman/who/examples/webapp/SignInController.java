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
package org.jwcarman.who.examples.webapp;

import org.jwcarman.who.core.domain.WhoPrincipal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The sign-in pages. They are listed in {@code who.security.public-paths}; the work is done in the
 * browser by {@code who.js}, against who's own endpoints.
 */
@Controller
class SignInController {

  private final Providers providers;
  private final String turnstileSiteKey;

  SignInController(
      Providers providers, @Value("${example.turnstile-site-key}") String turnstileSiteKey) {
    this.providers = providers;
    this.turnstileSiteKey = turnstileSiteKey;
  }

  /**
   * The sign-in page. Someone already signed in goes to the board, except on the way back from a
   * provider ({@code ?error=...}): a provider sign-in by someone signed in lands here, so the page
   * says so and offers to sign out.
   */
  @GetMapping("/signin")
  String signIn(
      @AuthenticationPrincipal WhoPrincipal principal,
      @RequestParam(required = false) String error,
      Model model) {
    if (principal != null && error == null) {
      return "redirect:/";
    }
    model.addAttribute("signedIn", principal != null);
    model.addAttribute("providers", providers.all());
    // the page gets a Turnstile token with it for each email request
    model.addAttribute("turnstileSiteKey", turnstileSiteKey);
    return "signin";
  }

  /** The page {@code who.email-link.link-base-url} points at: it redeems the link's token. */
  @GetMapping("/signin/link")
  String link(Model model) {
    model.addAttribute("turnstileSiteKey", turnstileSiteKey);
    return "signin-link";
  }
}
