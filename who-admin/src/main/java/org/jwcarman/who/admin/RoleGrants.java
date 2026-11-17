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

import java.util.Collection;
import java.util.Set;

import org.jwcarman.who.rbac.RbacService;
import org.jwcarman.who.rbac.Role;

/**
 * Who may grant which roles through who's admin endpoints: the single place that policy lives. Both
 * {@code PUT /identities/{id}/roles/{role}} and {@code POST /invitations} ask it, after every role
 * is known to exist and before anything changes. Removing a role is not restricted, and {@code
 * InvitationService}, for trusted application code, applies no policy.
 */
final class RoleGrants {

  private RoleGrants() {}

  /**
   * Refuses a grant that would escalate: the caller may grant a role only if its own permissions
   * include every permission the role carries. Its permissions are those of its authentication, so
   * an API key has only the permissions its scopes keep, not all of its owner's. A role that
   * carries no permissions may be granted by anyone allowed to manage identities.
   *
   * @param rbac reads each role's permissions
   * @param callerPermissions the caller's permissions
   * @param roles the roles to grant, each known to exist
   * @throws RoleGrantForbiddenException if a role carries a permission the caller lacks
   */
  static void requireMayGrant(
      RbacService rbac, Set<String> callerPermissions, Collection<Role> roles) {
    for (Role role : roles) {
      if (!callerPermissions.containsAll(rbac.findPermissions(role))) {
        throw new RoleGrantForbiddenException();
      }
    }
  }
}
