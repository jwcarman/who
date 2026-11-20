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
package org.jwcarman.who.emaillink;

import static java.util.Objects.requireNonNull;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.rbac.RbacService;
import org.jwcarman.who.rbac.Role;
import org.jwcarman.who.rbac.RoleNotFoundException;

/**
 * The {@link InvitationRoles} of {@code who-rbac}: assigns each role by name with {@link
 * RbacService#assignRoleByName(Identity, String)}, and names an identity's roles with {@link
 * RbacService#findRolesForIdentity(UUID)}. Loaded only when {@code who-rbac} is on the classpath.
 */
public final class RbacInvitationRoles implements InvitationRoles {

  private final RbacService rbac;

  /**
   * Creates the adapter.
   *
   * @param rbac assigns the roles
   */
  public RbacInvitationRoles(RbacService rbac) {
    this.rbac = requireNonNull(rbac, "rbac must not be null");
  }

  @Override
  public void assign(Identity identity, Set<String> roleNames) {
    for (String roleName : roleNames) {
      try {
        rbac.assignRoleByName(identity, roleName);
      } catch (RoleNotFoundException _) {
        throw new InvalidInvitationException("No such role");
      }
    }
  }

  @Override
  public Set<String> namesOf(UUID identityId) {
    return rbac.findRolesForIdentity(identityId).stream()
        .map(Role::name)
        .collect(Collectors.toUnmodifiableSet());
  }
}
