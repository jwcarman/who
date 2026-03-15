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

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcRolePermissionRepositoryTest extends AbstractRbacTest {

    @Autowired
    private JdbcRoleRepository roleRepository;

    @Autowired
    private JdbcRolePermissionRepository permissionRepository;

    @Test
    void addsAndRetrievesPermissionsByRoleId() {
        Role role = roleRepository.save(Role.create("PERM_ROLE_1"));

        permissionRepository.addPermission(role.id(), "READ");
        permissionRepository.addPermission(role.id(), "WRITE");

        assertThat(permissionRepository.findPermissionsByRoleId(role.id()))
                .containsExactlyInAnyOrder("READ", "WRITE");
    }

    @Test
    void removesPermission() {
        Role role = roleRepository.save(Role.create("PERM_ROLE_2"));
        permissionRepository.addPermission(role.id(), "DELETE");
        permissionRepository.addPermission(role.id(), "EXECUTE");

        permissionRepository.removePermission(role.id(), "DELETE");

        assertThat(permissionRepository.findPermissionsByRoleId(role.id()))
                .containsOnly("EXECUTE");
    }

    @Test
    void removeAllPermissionsForRole() {
        Role role = roleRepository.save(Role.create("PERM_ROLE_3"));
        permissionRepository.addPermission(role.id(), "A");
        permissionRepository.addPermission(role.id(), "B");

        permissionRepository.removeAllPermissionsForRole(role.id());

        assertThat(permissionRepository.findPermissionsByRoleId(role.id())).isEmpty();
    }

    @Test
    void findPermissionsByRoleIdsReturnsUnion() {
        Role role1 = roleRepository.save(Role.create("PERM_ROLE_4"));
        Role role2 = roleRepository.save(Role.create("PERM_ROLE_5"));
        permissionRepository.addPermission(role1.id(), "READ");
        permissionRepository.addPermission(role2.id(), "WRITE");

        assertThat(permissionRepository.findPermissionsByRoleIds(List.of(role1.id(), role2.id())))
                .containsExactlyInAnyOrder("READ", "WRITE");
    }

    @Test
    void findPermissionsByRoleIdsDeduplicatesOverlappingPermissions() {
        Role role1 = roleRepository.save(Role.create("PERM_ROLE_6"));
        Role role2 = roleRepository.save(Role.create("PERM_ROLE_7"));
        permissionRepository.addPermission(role1.id(), "SHARED");
        permissionRepository.addPermission(role2.id(), "SHARED");

        assertThat(permissionRepository.findPermissionsByRoleIds(List.of(role1.id(), role2.id())))
                .containsExactly("SHARED");
    }

    @Test
    void findPermissionsByRoleIdReturnsEmptyWhenNoneAssigned() {
        Role role = roleRepository.save(Role.create("PERM_ROLE_8"));

        assertThat(permissionRepository.findPermissionsByRoleId(role.id())).isEmpty();
    }

    @Test
    void findPermissionsByRoleIdsReturnsEmptySetForEmptyInput() {
        assertThat(permissionRepository.findPermissionsByRoleIds(List.of())).isEmpty();
    }
}
