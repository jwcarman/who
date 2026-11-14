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
package org.jwcarman.who.nativetests.oidc;

import java.util.List;
import java.util.Map;

import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.federated.FederatedCredentialService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Endpoints the native tests set the application up through; public under /test/**. */
@RestController
class TestController {

  private final EventRecorder recorder;
  private final WhoService who;
  private final FederatedCredentialService credentials;
  private final SwitchableSignUpPolicy policy;
  private final String issuer;

  TestController(
      EventRecorder recorder,
      WhoService who,
      FederatedCredentialService credentials,
      SwitchableSignUpPolicy policy,
      @Value("${spring.security.oauth2.client.provider.test.issuer-uri}") String issuer) {
    this.recorder = recorder;
    this.who = who;
    this.credentials = credentials;
    this.policy = policy;
    this.issuer = issuer;
  }

  record LinkedAccount(String subject) {}

  record Policy(boolean open) {}

  @GetMapping("/test/events")
  List<Map<String, String>> events() {
    return recorder.events();
  }

  @DeleteMapping("/test/events")
  ResponseEntity<Void> clearEvents() {
    recorder.clear();
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/test/setup/linked")
  Map<String, String> setUpLinked(@RequestBody LinkedAccount account) {
    Identity identity = who.createIdentity();
    credentials.link(identity, issuer, account.subject());
    return Map.of("identityId", identity.id().toString());
  }

  @PostMapping("/test/policy")
  ResponseEntity<Void> setPolicy(@RequestBody Policy setting) {
    policy.setOpen(setting.open());
    return ResponseEntity.noContent().build();
  }
}
