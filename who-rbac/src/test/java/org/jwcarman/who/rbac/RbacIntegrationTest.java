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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * End-to-end integration test covering the full role → permission → identity chain via RbacService
 * and RbacPermissionsResolver.
 */
class RbacIntegrationTest extends AbstractRbacTest {

  @Autowired private RbacService rbacService;

  @Autowired private RbacPermissionsResolver resolver;

  @Test
  void resolverReturnsPermissionsForIdentityWithRoles() {
    Role role = rbacService.createRole("FULL_ACCESS");
    rbacService.addPermissionToRole(role, "READ");
    rbacService.addPermissionToRole(role, "WRITE");

    Identity identity = Identity.create();
    rbacService.assignRoleToIdentity(identity, role);

    assertThat(resolver.resolve(identity)).containsExactlyInAnyOrder("READ", "WRITE");
  }

  @Test
  void resolverReturnsEmptySetWhenIdentityHasNoRoles() {
    Identity identity = Identity.create();
    assertThat(resolver.resolve(identity)).isEmpty();
  }

  @Test
  void resolverUnionsPermissionsFromMultipleRoles() {
    Role role1 = rbacService.createRole("MULTI_ROLE_1");
    Role role2 = rbacService.createRole("MULTI_ROLE_2");
    rbacService.addPermissionToRole(role1, "READ");
    rbacService.addPermissionToRole(role2, "WRITE");

    Identity identity = Identity.create();
    rbacService.assignRoleToIdentity(identity, role1);
    rbacService.assignRoleToIdentity(identity, role2);

    assertThat(resolver.resolve(identity)).containsExactlyInAnyOrder("READ", "WRITE");
  }

  @Test
  void createRoleThrowsWhenNameAlreadyExists() {
    rbacService.createRole("DUPLICATE");
    assertThatThrownBy(() -> rbacService.createRole("DUPLICATE"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void deleteRoleThrowsWhenNotFound() {
    Role unknownRole = Role.create("UNKNOWN");
    assertThatThrownBy(() -> rbacService.deleteRole(unknownRole))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void addPermissionToRoleThrowsWhenRoleNotFound() {
    Role unknownRole = Role.create("UNKNOWN");
    assertThatThrownBy(() -> rbacService.addPermissionToRole(unknownRole, "READ"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void removePermissionFromRoleThrowsWhenNotAssigned() {
    Role role = rbacService.createRole("REMOVE_PERM_ROLE");
    assertThatThrownBy(() -> rbacService.removePermissionFromRole(role, "NOT_ASSIGNED"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void assignRoleToIdentityThrowsWhenRoleNotFound() {
    Identity unknownIdentity = Identity.create();
    Role unknownRole = Role.create("UNKNOWN");
    assertThatThrownBy(() -> rbacService.assignRoleToIdentity(unknownIdentity, unknownRole))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void removeRoleFromIdentityThrowsWhenNotAssigned() {
    Role role = rbacService.createRole("REMOVE_ASSIGN_ROLE");
    Identity unknownIdentity = Identity.create();
    assertThatThrownBy(() -> rbacService.removeRoleFromIdentity(unknownIdentity, role))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void removePermissionFromRoleSucceeds() {
    Role role = rbacService.createRole("REMOVE_PERM_SUCCESS_ROLE");
    rbacService.addPermissionToRole(role, "DELETE");

    rbacService.removePermissionFromRole(role, "DELETE");

    Identity identity = Identity.create();
    rbacService.assignRoleToIdentity(identity, role);
    assertThat(resolver.resolve(identity)).doesNotContain("DELETE");
  }

  @Test
  void removeRoleFromIdentitySucceeds() {
    Role role = rbacService.createRole("REMOVE_ASSIGN_SUCCESS_ROLE");
    Identity identity = Identity.create();
    rbacService.assignRoleToIdentity(identity, role);

    rbacService.removeRoleFromIdentity(identity, role);

    assertThat(resolver.resolve(identity)).isEmpty();
  }

  @Test
  void findRequiredRoleReturnsRoleByName() {
    rbacService.createRole("FIND_ME");
    assertThatNoException()
        .isThrownBy(
            () -> {
              Role found = rbacService.findRequiredRole("FIND_ME");
              assertThat(found.name()).isEqualTo("FIND_ME");
            });
  }

  @Test
  void findRequiredRoleThrowsWhenNotFound() {
    assertThatThrownBy(() -> rbacService.findRequiredRole("NO_SUCH_ROLE"))
        .isInstanceOf(RoleNotFoundException.class);
  }

  @Test
  void deleteRoleCascadesPermissionsAndIdentityAssignments() {
    Role role = rbacService.createRole("CASCADE_ROLE");
    rbacService.addPermissionToRole(role, "READ");
    Identity identity = Identity.create();
    rbacService.assignRoleToIdentity(identity, role);

    rbacService.deleteRole(role);

    assertThat(resolver.resolve(identity)).isEmpty();
  }
}
