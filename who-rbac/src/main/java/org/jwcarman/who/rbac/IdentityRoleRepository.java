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

import java.util.List;
import java.util.UUID;

/**
 * Repository for managing the assignment of roles to identities.
 *
 * <p>There is no foreign key from {@code who_identity_role.identity_id} to the identity table — the
 * RBAC module does not own identity storage. The application is responsible for managing
 * referential integrity at the service level.
 */
public interface IdentityRoleRepository {

  /**
   * Returns the list of role UUIDs assigned to the given identity.
   *
   * @param identityId the identity UUID
   * @return list of role UUIDs (never null; empty if none)
   */
  List<UUID> findRoleIdsByIdentityId(UUID identityId);

  /**
   * Returns the ids of the identities that hold the given role, ascending by the identity id's
   * canonical string form (unsigned byte order), which is not {@link UUID#compareTo} order.
   *
   * @param roleId the role UUID
   * @return list of identity UUIDs (never null; empty if none)
   */
  List<UUID> findIdentityIdsByRoleId(UUID roleId);

  /**
   * Assigns a role to an identity.
   *
   * @param identityId the identity UUID
   * @param roleId the role UUID
   */
  void assignRole(UUID identityId, UUID roleId);

  /**
   * Removes a role assignment from an identity.
   *
   * @param identityId the identity UUID
   * @param roleId the role UUID
   */
  void removeRole(UUID identityId, UUID roleId);

  /**
   * Removes all role assignments for the given identity.
   *
   * @param identityId the identity UUID
   */
  void removeAllRolesForIdentity(UUID identityId);

  /**
   * Assigns a role to an identity and reports whether that changed anything.
   *
   * <p>The default implementation reads the identity's roles before assigning, so an implementation
   * should override it with a single write that reports the rows it affected.
   *
   * @param identityId the identity
   * @param roleId the role
   * @return {@code true} if the role was not yet assigned and now is
   */
  default boolean assignRoleIfAbsent(UUID identityId, UUID roleId) {
    if (findRoleIdsByIdentityId(identityId).contains(roleId)) {
      return false;
    }
    assignRole(identityId, roleId);
    return true;
  }

  /**
   * Removes a role assignment from an identity and reports whether that changed anything.
   *
   * <p>The default implementation reads the identity's roles before removing, so an implementation
   * should override it with a single write that reports the rows it affected.
   *
   * @param identityId the identity
   * @param roleId the role
   * @return {@code true} if the role was assigned and now is not
   */
  default boolean removeRoleIfPresent(UUID identityId, UUID roleId) {
    if (!findRoleIdsByIdentityId(identityId).contains(roleId)) {
      return false;
    }
    removeRole(identityId, roleId);
    return true;
  }
}
