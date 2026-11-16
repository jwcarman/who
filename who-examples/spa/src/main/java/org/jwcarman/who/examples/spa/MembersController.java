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
package org.jwcarman.who.examples.spa;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jwcarman.who.emaillink.EmailAddressTakenException;
import org.jwcarman.who.emaillink.EmailCredential;
import org.jwcarman.who.emaillink.EmailCredentialService;
import org.jwcarman.who.rbac.RbacService;
import org.jwcarman.who.spring.security.RequiresPermission;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Members and their roles, and invites by email. */
@RestController
class MembersController {

  /**
   * One member.
   *
   * @param identityId the member's identity
   * @param email their oldest email address, or {@code null} if they signed up with a provider
   * @param roles their roles
   */
  record Member(UUID identityId, String email, List<TeamRole> roles) {}

  /**
   * An invite.
   *
   * @param email the address to invite
   */
  record Invite(String email) {}

  private final RbacService rbacService;
  private final EmailCredentialService emailCredentials;
  private final Invitations invitations;

  MembersController(
      RbacService rbacService, EmailCredentialService emailCredentials, Invitations invitations) {
    this.rbacService = rbacService;
    this.emailCredentials = emailCredentials;
    this.invitations = invitations;
  }

  @GetMapping("/api/members")
  @RequiresPermission(Permissions.MEMBER_MANAGE)
  List<Member> members() {
    Map<UUID, List<TeamRole>> roles = new LinkedHashMap<>();
    for (TeamRole teamRole : TeamRole.values()) {
      for (UUID id : rbacService.findIdentityIdsWithRole(rbacService.findRequiredRole(teamRole))) {
        roles.computeIfAbsent(id, k -> new ArrayList<>()).add(teamRole);
      }
    }
    List<Member> members = new ArrayList<>();
    roles.forEach(
        (id, list) ->
            members.add(
                new Member(
                    id,
                    emailCredentials.findOldestFor(id).map(EmailCredential::address).orElse(null),
                    list)));
    return members;
  }

  @PostMapping("/api/members")
  @RequiresPermission(Permissions.MEMBER_MANAGE)
  ResponseEntity<ProblemDetail> invite(@RequestBody Invite invite) {
    try {
      invitations.invite(invite.email() == null ? "" : invite.email().strip());
      return ResponseEntity.noContent().build();
    } catch (EmailAddressTakenException _) {
      return ResponseEntity.status(HttpStatus.CONFLICT)
          .body(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Already has an account"));
    } catch (IllegalArgumentException _) {
      return ResponseEntity.badRequest()
          .body(
              ProblemDetail.forStatusAndDetail(
                  HttpStatus.BAD_REQUEST, "That is not an email address"));
    }
  }
}
