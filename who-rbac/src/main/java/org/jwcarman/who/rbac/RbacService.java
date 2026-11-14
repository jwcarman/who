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

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.event.WhoEventPublisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
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
  private final WhoEventPublisher eventPublisher;
  private final Clock clock;

  /**
   * Creates a service that publishes no events.
   *
   * @param roleRepository where roles are stored
   * @param rolePermissionRepository where role permissions are stored
   * @param identityRoleRepository where identity-role assignments are stored
   */
  @Autowired
  public RbacService(
      RoleRepository roleRepository,
      RolePermissionRepository rolePermissionRepository,
      IdentityRoleRepository identityRoleRepository) {
    this(
        roleRepository,
        rolePermissionRepository,
        identityRoleRepository,
        WhoEventPublisher.NOOP,
        Clock.systemUTC());
  }

  /**
   * Creates a service that publishes an event for each change it makes.
   *
   * @param roleRepository where roles are stored
   * @param rolePermissionRepository where role permissions are stored
   * @param identityRoleRepository where identity-role assignments are stored
   * @param eventPublisher where the role events are published
   * @param clock the clock events are stamped with
   */
  public RbacService(
      RoleRepository roleRepository,
      RolePermissionRepository rolePermissionRepository,
      IdentityRoleRepository identityRoleRepository,
      WhoEventPublisher eventPublisher,
      Clock clock) {
    this.roleRepository = roleRepository;
    this.rolePermissionRepository = rolePermissionRepository;
    this.identityRoleRepository = identityRoleRepository;
    this.eventPublisher = eventPublisher;
    this.clock = clock;
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
   * Returns the role with the given name, if there is one.
   *
   * @param name the role name to look up
   * @return the role, or empty if no role has that name
   */
  public Optional<Role> findRole(String name) {
    return roleRepository.findByName(name);
  }

  /**
   * Returns the role whose name matches the enum constant, if there is one.
   *
   * @param <R> the enum type serving as the role name registry
   * @param role the enum constant whose {@link Enum#name()} is looked up
   * @return the role, or empty if no role has that name
   */
  public <R extends Enum<R>> Optional<Role> findRole(R role) {
    return findRole(role.name());
  }

  /**
   * Returns every role, sorted by name.
   *
   * @return the roles (never null; empty if none)
   */
  public List<Role> listRoles() {
    return roleRepository.findAll();
  }

  /**
   * Returns the ids of the identities that hold the given role, ascending by the identity id's
   * canonical string form (unsigned byte order), which is not {@link UUID#compareTo} order.
   *
   * @param role the role whose holders are wanted
   * @return the identity ids (never null; empty if none)
   */
  public List<UUID> findIdentityIdsWithRole(Role role) {
    return identityRoleRepository.findIdentityIdsByRoleId(role.id());
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
   * @throws RoleAlreadyExistsException if a role with that name already exists (an {@link
   *     IllegalArgumentException})
   */
  @Transactional
  public <R extends Enum<R>> Role createRole(R role) {
    return createRoleByName(role.name());
  }

  /**
   * Creates a new role with the given name.
   *
   * @param name the role name (must be unique)
   * @return the newly created role
   * @throws RoleAlreadyExistsException if a role with that name already exists (an {@link
   *     IllegalArgumentException})
   */
  @Transactional
  public Role createRole(String name) {
    return createRoleByName(name);
  }

  private Role createRoleByName(String name) {
    if (roleRepository.findByName(name).isPresent()) {
      throw new RoleAlreadyExistsException();
    }
    try {
      Role saved = roleRepository.save(Role.create(name));
      eventPublisher.publish(new RoleCreated(clock.instant(), saved.id(), saved.name()));
      return saved;
    } catch (DuplicateKeyException e) {
      // A concurrent create won the race between the check above and this insert.
      throw new RoleAlreadyExistsException(e);
    }
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
    if (roleRepository.deleteByIdIfPresent(role.id())) {
      eventPublisher.publish(new RoleDeleted(clock.instant(), role.id(), role.name()));
    }
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
    if (rolePermissionRepository.addPermissionIfAbsent(role.id(), permission)) {
      eventPublisher.publish(
          new RolePermissionAdded(clock.instant(), role.id(), role.name(), permission));
    }
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
    if (!rolePermissionRepository.removePermissionIfPresent(role.id(), permission)) {
      throw new IllegalArgumentException(
          "Permission '" + permission + "' is not assigned to role: " + role.id());
    }
    eventPublisher.publish(
        new RolePermissionRemoved(clock.instant(), role.id(), role.name(), permission));
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
    assign(identity, role);
  }

  private void assign(Identity identity, Role role) {
    if (identityRoleRepository.assignRoleIfAbsent(identity.id(), role.id())) {
      eventPublisher.publish(
          new RoleAssigned(clock.instant(), identity.id(), role.id(), role.name()));
    }
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
    assignRoleByNameInternal(identity, role.name());
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
    assignRoleByNameInternal(identity, roleName);
  }

  private void assignRoleByNameInternal(Identity identity, String roleName) {
    Role role =
        roleRepository.findByName(roleName).orElseThrow(() -> new RoleNotFoundException(roleName));
    assign(identity, role);
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
    if (!identityRoleRepository.removeRoleIfPresent(identity.id(), role.id())) {
      throw new IllegalArgumentException(
          "Role " + role.id() + " is not assigned to identity: " + identity.id());
    }
    eventPublisher.publish(
        new RoleUnassigned(clock.instant(), identity.id(), role.id(), role.name()));
  }
}
