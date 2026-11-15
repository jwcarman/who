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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Invites a person by email: an identity with that address and the member role. They sign in with
 * an email link; who sends a link only to an address it knows.
 */
@Service
class Invitations {

  private final WhoService whoService;
  private final EmailCredentialService emailCredentials;
  private final RbacService rbacService;

  Invitations(
      WhoService whoService, EmailCredentialService emailCredentials, RbacService rbacService) {
    this.whoService = whoService;
    this.emailCredentials = emailCredentials;
    this.rbacService = rbacService;
  }

  /**
   * Invites {@code address}.
   *
   * @throws org.jwcarman.who.emaillink.EmailAddressTakenException if someone already has it
   */
  @Transactional
  void invite(String address) {
    Identity identity = whoService.createIdentity();
    emailCredentials.register(identity, address);
    rbacService.assignRoleByName(identity, TeamRole.MEMBER);
  }
}
