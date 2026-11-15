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
 * Creates the roles and the first admin on startup, once. Everyone else is invited by an admin or
 * signs up through the {@link SignUpConfig sign-up policy}.
 */
@Component
class Setup implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(Setup.class);

  private final RbacService rbacService;
  private final WhoService whoService;
  private final EmailCredentialService emailCredentialService;
  private final ExampleProperties properties;

  Setup(
      RbacService rbacService,
      WhoService whoService,
      EmailCredentialService emailCredentialService,
      ExampleProperties properties) {
    this.rbacService = rbacService;
    this.whoService = whoService;
    this.emailCredentialService = emailCredentialService;
    this.properties = properties;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    for (TeamRole teamRole : TeamRole.values()) {
      if (rbacService.findRole(teamRole).isEmpty()) {
        Role role = rbacService.createRole(teamRole);
        teamRole.permissions().forEach(p -> rbacService.addPermissionToRole(role, p));
      }
    }
    if (emailCredentialService.findByAddress(properties.adminEmail()).isEmpty()) {
      Identity admin = whoService.createIdentity();
      emailCredentialService.register(admin, properties.adminEmail());
      rbacService.assignRoleByName(admin, TeamRole.ADMIN);
      log.info(
          "Created the first admin, {}. Sign in with an email link; the email is at"
              + " http://localhost:8025",
          properties.adminEmail());
    }
  }
}
