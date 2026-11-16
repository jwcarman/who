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
package org.jwcarman.who.examples.observability;

import java.util.List;

import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.emaillink.EmailCredentialService;
import org.jwcarman.who.rbac.RbacService;
import org.jwcarman.who.rbac.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The people this example signs in, each holding both of the API's permissions: {@value
 * #DEMO_EMAIL} for the page, and two for the traffic generator. An example shortcut; a real
 * application decides who may sign in.
 */
@Component
class Setup implements ApplicationRunner {

  /** The demo person's address. Every sign-in email lands in Mailpit. */
  static final String DEMO_EMAIL = "demo@example.com";

  /** The traffic generator's own person: it signs in by code and owns the generator's keys. */
  static final String TRAFFIC_EMAIL = "traffic@example.com";

  /** The person the traffic generator signs in as with wrong codes. */
  static final String WRONG_CODE_EMAIL = "wrong-code@example.com";

  /** The role that holds the API's permissions. */
  static final String ROLE = "DEMO";

  private static final Logger log = LoggerFactory.getLogger(Setup.class);

  private final RbacService rbacService;
  private final WhoService whoService;
  private final EmailCredentialService emailCredentials;

  Setup(RbacService rbacService, WhoService whoService, EmailCredentialService emailCredentials) {
    this.rbacService = rbacService;
    this.whoService = whoService;
    this.emailCredentials = emailCredentials;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (rbacService.findRole(ROLE).isEmpty()) {
      Role role = rbacService.createRole(ROLE);
      rbacService.addPermissionToRole(role, Permissions.GREETING_READ);
      rbacService.addPermissionToRole(role, Permissions.GREETING_WRITE);
    }
    for (String address : List.of(DEMO_EMAIL, TRAFFIC_EMAIL, WRONG_CODE_EMAIL)) {
      if (emailCredentials.findByAddress(address).isEmpty()) {
        Identity identity = whoService.createIdentity();
        emailCredentials.register(identity, address);
        rbacService.assignRoleByName(identity, ROLE);
        log.info("Created {}", address);
      }
    }
  }
}
