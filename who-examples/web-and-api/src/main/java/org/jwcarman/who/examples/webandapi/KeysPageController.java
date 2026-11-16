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

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jwcarman.who.apikey.ApiKeyService;
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/**
 * A person's API keys, on the website: made, listed and revoked by the person, signed in with a
 * session, never by a key. The API has no way to manage keys, so a leaked key cannot make itself a
 * broader or longer-lived one, see its owner's other keys, or revoke them. {@link ApiKeyService}
 * trusts its caller, so this controller checks that a key is the person's before revoking it.
 */
@Controller
class KeysPageController {

  private final ApiKeyService apiKeys;

  KeysPageController(ApiKeyService apiKeys) {
    this.apiKeys = apiKeys;
  }

  @GetMapping("/keys")
  String keys(@AuthenticationPrincipal WhoPrincipal principal, Model model) {
    return page(principal, model);
  }

  /**
   * Makes a key and shows it, this once: who keeps only its hash. who refuses a blank or long name,
   * a lifetime over {@code who.api-key.max-lifetime}, no scopes, or a scope the person lacks.
   */
  @PostMapping("/keys")
  String make(
      @RequestParam String name,
      @RequestParam int lifetimeDays,
      @RequestParam(required = false) Set<String> scopes,
      @AuthenticationPrincipal WhoPrincipal principal,
      Model model) {
    try {
      model.addAttribute(
          "newKey",
          apiKeys.create(
              principal.identity(),
              name,
              Duration.ofDays(lifetimeDays),
              scopes == null ? Set.of() : scopes));
    } catch (IllegalArgumentException e) {
      model.addAttribute("error", e.getMessage());
    }
    return page(principal, model);
  }

  /** Revokes one of the person's keys; another's id is answered as unknown. */
  @PostMapping("/keys/{id}/revoke")
  String revoke(@PathVariable UUID id, @AuthenticationPrincipal WhoPrincipal principal) {
    boolean owned =
        apiKeys.findByIdentity(principal.identity().id()).stream().anyMatch(k -> k.id().equals(id));
    if (!owned) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such key");
    }
    apiKeys.revoke(id);
    return "redirect:/keys";
  }

  private String page(WhoPrincipal principal, Model model) {
    model.addAttribute("keys", apiKeys.findByIdentity(principal.identity().id()));
    // only permissions the person holds may be scopes
    List<String> held = principal.permissions().stream().sorted().toList();
    model.addAttribute("permissions", held);
    return "keys";
  }
}
