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
package org.jwcarman.who.nativetests.emaillink;

import java.util.List;
import java.util.Map;

import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.emaillink.EmailCredentialService;
import org.jwcarman.who.rbac.RbacService;
import org.jwcarman.who.rbac.Role;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Endpoints for the test only: recorded events, captured mail, and setting up identities. */
@RestController
class TestController {

  static final String ADMIN_ROLE = "native-admin";

  private final EventRecorder recorder;
  private final CapturingMailSender mail;
  private final WhoService who;
  private final EmailCredentialService emails;
  private final RbacService rbac;

  TestController(
      EventRecorder recorder,
      CapturingMailSender mail,
      WhoService who,
      EmailCredentialService emails,
      RbacService rbac) {
    this.recorder = recorder;
    this.mail = mail;
    this.who = who;
    this.emails = emails;
    this.rbac = rbac;
  }

  /** The body of {@code POST /test/setup/identity}. */
  record IdentitySetup(String email, boolean admin) {}

  @GetMapping("/test/events")
  List<Map<String, String>> events() {
    return recorder.events();
  }

  @DeleteMapping("/test/events")
  ResponseEntity<Void> clearEvents() {
    recorder.clear();
    return ResponseEntity.noContent().build();
  }

  @GetMapping(value = "/test/mail", produces = "text/plain")
  ResponseEntity<String> mail(@RequestParam("to") String address) {
    return ResponseEntity.of(mail.lastTo(address));
  }

  @PostMapping("/test/setup/identity")
  Map<String, String> setUpIdentity(@RequestBody IdentitySetup setup) {
    Identity identity = who.createIdentity();
    emails.register(identity, setup.email());
    if (setup.admin()) {
      rbac.assignRoleToIdentity(identity, adminRole());
    }
    return Map.of("identityId", identity.id().toString());
  }

  private synchronized Role adminRole() {
    return rbac.findRole(ADMIN_ROLE)
        .orElseGet(
            () -> {
              Role role = rbac.createRole(ADMIN_ROLE);
              rbac.addPermissionToRole(role, ApiController.ADMIN_PERMISSION);
              return role;
            });
  }
}
