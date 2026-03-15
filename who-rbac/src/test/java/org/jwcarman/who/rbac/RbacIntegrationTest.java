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

import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.IdentityStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end integration test covering the full role → permission → identity chain
 * via RbacService and RbacPermissionsResolver.
 */
class RbacIntegrationTest extends AbstractRbacTest {

    @Autowired
    private RbacService rbacService;

    @Autowired
    private RbacPermissionsResolver resolver;

    @Test
    void resolverReturnsPermissionsForIdentityWithRoles() {
        UUID roleId = rbacService.createRole("FULL_ACCESS");
        rbacService.addPermissionToRole(roleId, "READ");
        rbacService.addPermissionToRole(roleId, "WRITE");

        UUID identityId = UUID.randomUUID();
        rbacService.assignRoleToIdentity(identityId, roleId);

        Identity identity = Identity.create(identityId, IdentityStatus.ACTIVE);
        assertThat(resolver.resolve(identity)).containsExactlyInAnyOrder("READ", "WRITE");
    }

    @Test
    void resolverReturnsEmptySetWhenIdentityHasNoRoles() {
        Identity identity = Identity.create(UUID.randomUUID(), IdentityStatus.ACTIVE);
        assertThat(resolver.resolve(identity)).isEmpty();
    }

    @Test
    void resolverUnionsPermissionsFromMultipleRoles() {
        UUID role1Id = rbacService.createRole("MULTI_ROLE_1");
        UUID role2Id = rbacService.createRole("MULTI_ROLE_2");
        rbacService.addPermissionToRole(role1Id, "READ");
        rbacService.addPermissionToRole(role2Id, "WRITE");

        UUID identityId = UUID.randomUUID();
        rbacService.assignRoleToIdentity(identityId, role1Id);
        rbacService.assignRoleToIdentity(identityId, role2Id);

        Identity identity = Identity.create(identityId, IdentityStatus.ACTIVE);
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
        assertThatThrownBy(() -> rbacService.deleteRole(UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addPermissionToRoleThrowsWhenRoleNotFound() {
        assertThatThrownBy(() -> rbacService.addPermissionToRole(UUID.randomUUID(), "READ"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void removePermissionFromRoleThrowsWhenNotAssigned() {
        UUID roleId = rbacService.createRole("REMOVE_PERM_ROLE");
        assertThatThrownBy(() -> rbacService.removePermissionFromRole(roleId, "NOT_ASSIGNED"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void assignRoleToIdentityThrowsWhenRoleNotFound() {
        assertThatThrownBy(() -> rbacService.assignRoleToIdentity(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void removeRoleFromIdentityThrowsWhenNotAssigned() {
        UUID roleId = rbacService.createRole("REMOVE_ASSIGN_ROLE");
        assertThatThrownBy(() -> rbacService.removeRoleFromIdentity(UUID.randomUUID(), roleId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deleteRoleCascadesPermissionsAndIdentityAssignments() {
        UUID roleId = rbacService.createRole("CASCADE_ROLE");
        rbacService.addPermissionToRole(roleId, "READ");
        UUID identityId = UUID.randomUUID();
        rbacService.assignRoleToIdentity(identityId, roleId);

        rbacService.deleteRole(roleId);

        // After cascade delete, identity has no roles → resolver returns empty
        Identity identity = Identity.create(identityId, IdentityStatus.ACTIVE);
        assertThat(resolver.resolve(identity)).isEmpty();
    }
}
