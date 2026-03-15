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
package org.jwcarman.who.rbac;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/** Repository for managing the association between roles and permission strings. */
public interface RolePermissionRepository {

  /**
   * Returns all permission strings assigned to the given role.
   *
   * @param roleId the role UUID
   * @return set of permission strings (never null; empty if none)
   */
  Set<String> findPermissionsByRoleId(UUID roleId);

  /**
   * Returns the union of all permission strings assigned to the given roles.
   *
   * @param roleIds the collection of role UUIDs
   * @return set of permission strings (never null; empty if none)
   */
  Set<String> findPermissionsByRoleIds(Collection<UUID> roleIds);

  /**
   * Assigns a permission string to the given role.
   *
   * @param roleId the role UUID
   * @param permission the permission string
   */
  void addPermission(UUID roleId, String permission);

  /**
   * Removes a permission string from the given role.
   *
   * @param roleId the role UUID
   * @param permission the permission string
   */
  void removePermission(UUID roleId, String permission);

  /**
   * Removes all permissions assigned to the given role.
   *
   * @param roleId the role UUID
   */
  void removeAllPermissionsForRole(UUID roleId);
}
