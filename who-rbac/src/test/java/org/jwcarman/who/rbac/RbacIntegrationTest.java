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

        Identity identity = Identity.create();
        rbacService.assignRoleToIdentity(identity, roleId);

        assertThat(resolver.resolve(identity)).containsExactlyInAnyOrder("READ", "WRITE");
    }

    @Test
    void resolverReturnsEmptySetWhenIdentityHasNoRoles() {
        Identity identity = Identity.create();
        assertThat(resolver.resolve(identity)).isEmpty();
    }

    @Test
    void resolverUnionsPermissionsFromMultipleRoles() {
        UUID role1Id = rbacService.createRole("MULTI_ROLE_1");
        UUID role2Id = rbacService.createRole("MULTI_ROLE_2");
        rbacService.addPermissionToRole(role1Id, "READ");
        rbacService.addPermissionToRole(role2Id, "WRITE");

        Identity identity = Identity.create();
        rbacService.assignRoleToIdentity(identity, role1Id);
        rbacService.assignRoleToIdentity(identity, role2Id);

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
        UUID unknownRoleId = UUID.randomUUID();
        assertThatThrownBy(() -> rbacService.deleteRole(unknownRoleId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addPermissionToRoleThrowsWhenRoleNotFound() {
        UUID unknownRoleId = UUID.randomUUID();
        assertThatThrownBy(() -> rbacService.addPermissionToRole(unknownRoleId, "READ"))
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
        Identity unknownIdentity = Identity.create();
        UUID unknownRoleId = UUID.randomUUID();
        assertThatThrownBy(() -> rbacService.assignRoleToIdentity(unknownIdentity, unknownRoleId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void removeRoleFromIdentityThrowsWhenNotAssigned() {
        UUID roleId = rbacService.createRole("REMOVE_ASSIGN_ROLE");
        Identity unknownIdentity = Identity.create();
        assertThatThrownBy(() -> rbacService.removeRoleFromIdentity(unknownIdentity, roleId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void removePermissionFromRoleSucceeds() {
        UUID roleId = rbacService.createRole("REMOVE_PERM_SUCCESS_ROLE");
        rbacService.addPermissionToRole(roleId, "DELETE");

        rbacService.removePermissionFromRole(roleId, "DELETE");

        Identity identity = Identity.create();
        rbacService.assignRoleToIdentity(identity, roleId);
        assertThat(resolver.resolve(identity)).doesNotContain("DELETE");
    }

    @Test
    void removeRoleFromIdentitySucceeds() {
        UUID roleId = rbacService.createRole("REMOVE_ASSIGN_SUCCESS_ROLE");
        Identity identity = Identity.create();
        rbacService.assignRoleToIdentity(identity, roleId);

        rbacService.removeRoleFromIdentity(identity, roleId);

        assertThat(resolver.resolve(identity)).isEmpty();
    }

    @Test
    void deleteRoleCascadesPermissionsAndIdentityAssignments() {
        UUID roleId = rbacService.createRole("CASCADE_ROLE");
        rbacService.addPermissionToRole(roleId, "READ");
        Identity identity = Identity.create();
        rbacService.assignRoleToIdentity(identity, roleId);

        rbacService.deleteRole(roleId);

        assertThat(resolver.resolve(identity)).isEmpty();
    }
}
