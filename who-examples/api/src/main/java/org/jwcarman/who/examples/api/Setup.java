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
package org.jwcarman.who.examples.api;

import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.examples.api.ExampleProperties.Account;
import org.jwcarman.who.federated.FederatedCredentialRepository;
import org.jwcarman.who.federated.FederatedCredentialService;
import org.jwcarman.who.rbac.RbacService;
import org.jwcarman.who.rbac.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the roles, and an identity linked to each configured account, on startup, once. who signs
 * a token in only when its {@code (issuer, subject)} is linked to an identity: it never creates one
 * from a token, so the app decides which accounts exist.
 */
@Component
class Setup implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(Setup.class);

  private final RbacService rbacService;
  private final WhoService whoService;
  private final FederatedCredentialRepository federatedCredentials;
  private final FederatedCredentialService federatedCredentialService;
  private final ExampleProperties properties;

  Setup(
      RbacService rbacService,
      WhoService whoService,
      FederatedCredentialRepository federatedCredentials,
      FederatedCredentialService federatedCredentialService,
      ExampleProperties properties) {
    this.rbacService = rbacService;
    this.whoService = whoService;
    this.federatedCredentials = federatedCredentials;
    this.federatedCredentialService = federatedCredentialService;
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
    for (Account account : properties.accounts()) {
      if (federatedCredentials
          .findByIssuerAndSubject(properties.issuer(), account.subject())
          .isEmpty()) {
        Identity identity = whoService.createIdentity();
        federatedCredentialService.link(identity, properties.issuer(), account.subject());
        rbacService.assignRoleByName(identity, account.role());
        log.info("Linked account {} as {}", account.subject(), account.role());
      }
    }
  }
}
