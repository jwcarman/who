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
package org.jwcarman.who.nativetests.apikey;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jwcarman.who.apikey.ApiKeyService;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.rbac.RbacService;
import org.jwcarman.who.rbac.Role;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Endpoints for the test only: recorded events, and setting up identities with keys. */
@RestController
class TestController {

  static final String ADMIN_ROLE = "native-admin";
  static final String USER_ROLE = "native-user";

  private final EventRecorder recorder;
  private final WhoService who;
  private final ApiKeyService apiKeys;
  private final RbacService rbac;

  TestController(EventRecorder recorder, WhoService who, ApiKeyService apiKeys, RbacService rbac) {
    this.recorder = recorder;
    this.who = who;
    this.apiKeys = apiKeys;
    this.rbac = rbac;
  }

  /** The body of {@code POST /test/setup/api-key}. */
  record ApiKeySetup(boolean admin) {}

  @GetMapping("/test/events")
  List<Map<String, String>> events() {
    return recorder.events();
  }

  @DeleteMapping("/test/events")
  ResponseEntity<Void> clearEvents() {
    recorder.clear();
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/test/setup/api-key")
  Map<String, String> setUpApiKey(@RequestBody ApiKeySetup setup) {
    Identity identity = who.createIdentity();
    // a key holds only scopes its owner has
    rbac.assignRoleToIdentity(identity, role(USER_ROLE, ApiController.USER_PERMISSION));
    Set<String> scopes = new HashSet<>(Set.of(ApiController.USER_PERMISSION));
    if (setup.admin()) {
      rbac.assignRoleToIdentity(identity, role(ADMIN_ROLE, ApiController.ADMIN_PERMISSION));
      scopes.add(ApiController.ADMIN_PERMISSION);
    }
    String key = apiKeys.create(identity, "native test", Duration.ofDays(1), scopes).key();
    return Map.of("identityId", identity.id().toString(), "key", key);
  }

  private synchronized Role role(String name, String permission) {
    return rbac.findRole(name)
        .orElseGet(
            () -> {
              Role role = rbac.createRole(name);
              rbac.addPermissionToRole(role, permission);
              return role;
            });
  }
}
