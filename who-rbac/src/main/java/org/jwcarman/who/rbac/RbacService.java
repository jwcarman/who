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

import java.util.Set;

import org.jwcarman.who.core.domain.Identity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Management service for administering roles, permissions, and identity-role assignments. All
 * multi-step operations are transactional.
 */
@Service
public class RbacService {

  private final RoleRepository roleRepository;
  private final RolePermissionRepository rolePermissionRepository;
  private final IdentityRoleRepository identityRoleRepository;

  public RbacService(
      RoleRepository roleRepository,
      RolePermissionRepository rolePermissionRepository,
      IdentityRoleRepository identityRoleRepository) {
    this.roleRepository = roleRepository;
    this.rolePermissionRepository = rolePermissionRepository;
    this.identityRoleRepository = identityRoleRepository;
  }

  /**
   * Returns the role whose name matches the enum constant.
   *
   * @param <R> the enum type serving as the role name registry
   * @param role the enum constant whose {@link Enum#name()} is looked up
   * @return the role
   * @throws RoleNotFoundException if no role with that name exists
   */
  public <R extends Enum<R>> Role findRequiredRole(R role) {
    return findRequiredRole(role.name());
  }

  /**
   * Returns the role with the given name.
   *
   * @param name the role name to look up
   * @return the role
   * @throws RoleNotFoundException if no role with that name exists
   */
  public Role findRequiredRole(String name) {
    return roleRepository.findByName(name).orElseThrow(() -> new RoleNotFoundException(name));
  }

  /**
   * Creates a new role whose name is derived from the enum constant.
   *
   * @param <R> the enum type serving as the role name registry
   * @param role the enum constant whose {@link Enum#name()} becomes the role name
   * @return the newly created role
   * @throws IllegalArgumentException if a role with that name already exists
   */
  @Transactional
  public <R extends Enum<R>> Role createRole(R role) {
    return createRole(role.name());
  }

  /**
   * Creates a new role with the given name.
   *
   * @param name the role name (must be unique)
   * @return the newly created role
   * @throws IllegalArgumentException if a role with that name already exists
   */
  @Transactional
  public Role createRole(String name) {
    if (roleRepository.findByName(name).isPresent()) {
      throw new IllegalArgumentException("Role already exists with name: " + name);
    }
    Role role = Role.create(name);
    return roleRepository.save(role);
  }

  /**
   * Deletes the given role and cascades to all associated permissions and assignments.
   *
   * @param role the role to delete
   * @throws IllegalArgumentException if the role does not exist
   */
  @Transactional
  public void deleteRole(Role role) {
    if (!roleRepository.existsById(role.id())) {
      throw new IllegalArgumentException("Role not found: " + role.id());
    }
    roleRepository.deleteById(role.id());
  }

  /**
   * Grants a permission string to the given role.
   *
   * @param role the role to grant the permission to
   * @param permission the permission string to grant
   * @throws IllegalArgumentException if the role does not exist
   */
  @Transactional
  public void addPermissionToRole(Role role, String permission) {
    if (!roleRepository.existsById(role.id())) {
      throw new IllegalArgumentException("Role not found: " + role.id());
    }
    rolePermissionRepository.addPermission(role.id(), permission);
  }

  /**
   * Revokes a permission string from the given role.
   *
   * @param role the role to revoke the permission from
   * @param permission the permission string to revoke
   * @throws IllegalArgumentException if the permission is not currently assigned to the role
   */
  @Transactional
  public void removePermissionFromRole(Role role, String permission) {
    Set<String> current = rolePermissionRepository.findPermissionsByRoleId(role.id());
    if (!current.contains(permission)) {
      throw new IllegalArgumentException(
          "Permission '" + permission + "' is not assigned to role: " + role.id());
    }
    rolePermissionRepository.removePermission(role.id(), permission);
  }

  /**
   * Assigns a role to an identity.
   *
   * @param identity the identity to assign the role to
   * @param role the role to assign
   * @throws IllegalArgumentException if the role does not exist
   */
  @Transactional
  public void assignRoleToIdentity(Identity identity, Role role) {
    if (!roleRepository.existsById(role.id())) {
      throw new IllegalArgumentException("Role not found: " + role.id());
    }
    identityRoleRepository.assignRole(identity.id(), role.id());
  }

  /**
   * Assigns a role to an identity using an enum constant as the role name.
   *
   * @param <R> the enum type serving as the role name registry
   * @param identity the identity to assign the role to
   * @param role the enum constant whose {@link Enum#name()} is used as the role name
   * @throws RoleNotFoundException if no role with that name exists
   */
  @Transactional
  public <R extends Enum<R>> void assignRoleByName(Identity identity, R role) {
    assignRoleByName(identity, role.name());
  }

  /**
   * Assigns a role to an identity by role name.
   *
   * @param identity the identity to assign the role to
   * @param roleName the name of the role to assign
   * @throws RoleNotFoundException if no role with that name exists
   */
  @Transactional
  public void assignRoleByName(Identity identity, String roleName) {
    Role role = findRequiredRole(roleName);
    identityRoleRepository.assignRole(identity.id(), role.id());
  }

  /**
   * Removes a role assignment from an identity.
   *
   * @param identity the identity to remove the role from
   * @param role the role to remove
   * @throws IllegalArgumentException if the role is not currently assigned to the identity
   */
  @Transactional
  public void removeRoleFromIdentity(Identity identity, Role role) {
    if (!identityRoleRepository.findRoleIdsByIdentityId(identity.id()).contains(role.id())) {
      throw new IllegalArgumentException(
          "Role " + role.id() + " is not assigned to identity: " + identity.id());
    }
    identityRoleRepository.removeRole(identity.id(), role.id());
  }
}
