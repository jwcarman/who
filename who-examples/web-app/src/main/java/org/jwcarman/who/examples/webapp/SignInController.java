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

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The sign-in pages. They are listed in {@code who.security.public-paths}; the work is done in the
 * browser by {@code who.js}, against who's own endpoints.
 */
@Controller
class SignInController {

  private final Providers providers;

  SignInController(Providers providers) {
    this.providers = providers;
  }

  @GetMapping("/signin")
  String signIn(Model model) {
    model.addAttribute("providers", providers.all());
    return "signin";
  }

  /** The page {@code who.email-link.link-base-url} points at: it redeems the link's token. */
  @GetMapping("/signin/link")
  String link() {
    return "signin-link";
  }
}
