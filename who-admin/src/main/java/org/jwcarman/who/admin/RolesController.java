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
package org.jwcarman.who.admin;

import static java.util.Objects.requireNonNull;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.jwcarman.who.rbac.RbacService;
import org.jwcarman.who.spring.security.WhoPaths;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /who/roles} lists every role with its permissions, by name. It needs {@link
 * WhoAdminPermissions#IDENTITIES_READ}, checked by {@link AdminGate}; any who sign-in may call it.
 */
@RestController
public class RolesController {

  private final RbacService rbac;
  private final AdminGate gate;

  /**
   * Creates the controller.
   *
   * @param rbac lists the roles and their permissions
   * @param gate admits the caller
   */
  public RolesController(RbacService rbac, AdminGate gate) {
    this.rbac = requireNonNull(rbac, "rbac must not be null");
    this.gate = requireNonNull(gate, "gate must not be null");
  }

  /**
   * Every role, by name.
   *
   * @param request the request
   * @return the roles, each with its permissions sorted
   */
  @GetMapping(WhoPaths.MAPPING + "/roles")
  public List<RoleView> roles(HttpServletRequest request) {
    gate.admit(request, WhoAdminPermissions.IDENTITIES_READ, false);
    return rbac.listRoles().stream()
        .map(
            role ->
                new RoleView(role.name(), rbac.findPermissions(role).stream().sorted().toList()))
        .toList();
  }
}
