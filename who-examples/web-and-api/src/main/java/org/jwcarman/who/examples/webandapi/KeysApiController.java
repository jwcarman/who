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

import java.util.UUID;

import org.jwcarman.who.apikey.ApiKey;
import org.jwcarman.who.apikey.ApiKeyService;
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.event.SignInMethod;
import org.jwcarman.who.spring.security.WhoAuthenticationToken;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The API's one view of keys: a key reads itself, so a machine can see its scopes and expiry and
 * rotate in time, without seeing any other key. Keys are managed on the website.
 */
@RestController
class KeysApiController {

  private final ApiKeyService apiKeys;

  KeysApiController(ApiKeyService apiKeys) {
    this.apiKeys = apiKeys;
  }

  /** The key this request signed in with; a request with no key has no self: {@code 404}. */
  @GetMapping("/api/keys/self")
  ApiKey self(Authentication authentication) {
    if (!(authentication instanceof WhoAuthenticationToken who)
        || who.signInMethod().filter(SignInMethod.API_KEY::equals).isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "This request did not use an API key");
    }
    // the id comes from the sign-in, never from the request
    UUID id =
        who.credentialId()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such key"));
    WhoPrincipal principal = (WhoPrincipal) authentication.getPrincipal();
    return apiKeys.findByIdentity(principal.identity().id()).stream()
        .filter(k -> k.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such key"));
  }
}
