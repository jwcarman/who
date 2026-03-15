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
}
