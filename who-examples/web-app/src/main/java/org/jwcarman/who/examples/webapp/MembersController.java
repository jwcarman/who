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
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Admins see who is on the team and invite people by email. */
@Controller
class MembersController {

  /**
   * One row of the members table.
   *
   * @param identityId the member's identity
   * @param email their oldest email address, or {@code null} if they signed up with a provider
   * @param roles their roles
   */
  record Member(UUID identityId, String email, List<TeamRole> roles) {}

  private final RbacService rbacService;
  private final EmailCredentialService emailCredentials;
  private final Invitations invitations;

  MembersController(
      RbacService rbacService, EmailCredentialService emailCredentials, Invitations invitations) {
    this.rbacService = rbacService;
    this.emailCredentials = emailCredentials;
    this.invitations = invitations;
  }

  @GetMapping("/members")
  @RequiresPermission(Permissions.MEMBER_MANAGE)
  String members(Model model) {
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
    model.addAttribute("members", members);
    return "members";
  }

  @PostMapping("/members")
  @RequiresPermission(Permissions.MEMBER_MANAGE)
  String invite(@RequestParam String email, RedirectAttributes redirect) {
    try {
      invitations.invite(email.strip());
      redirect.addFlashAttribute("message", "Invited " + email.strip() + ".");
    } catch (EmailAddressTakenException e) {
      redirect.addFlashAttribute("message", email.strip() + " already has an account.");
    } catch (IllegalArgumentException e) {
      redirect.addFlashAttribute("message", "That is not an email address.");
    }
    return "redirect:/members";
  }
}
