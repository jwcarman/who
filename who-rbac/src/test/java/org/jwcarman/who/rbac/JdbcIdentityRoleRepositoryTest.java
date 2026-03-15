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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcIdentityRoleRepositoryTest extends AbstractRbacTest {

    @Autowired
    private JdbcRoleRepository roleRepository;

    @Autowired
    private JdbcIdentityRoleRepository identityRoleRepository;

    @Test
    void assignsRoleToIdentityAndRetrievesIt() {
        Role role = roleRepository.save(Role.create("IR_ROLE_1"));
        UUID identityId = UUID.randomUUID();

        identityRoleRepository.assignRole(identityId, role.id());

        assertThat(identityRoleRepository.findRoleIdsByIdentityId(identityId))
                .containsExactly(role.id());
    }

    @Test
    void removesRoleFromIdentity() {
        Role role = roleRepository.save(Role.create("IR_ROLE_2"));
        UUID identityId = UUID.randomUUID();
        identityRoleRepository.assignRole(identityId, role.id());

        identityRoleRepository.removeRole(identityId, role.id());

        assertThat(identityRoleRepository.findRoleIdsByIdentityId(identityId)).isEmpty();
    }

    @Test
    void removeAllRolesForIdentity() {
        Role role1 = roleRepository.save(Role.create("IR_ROLE_3"));
        Role role2 = roleRepository.save(Role.create("IR_ROLE_4"));
        UUID identityId = UUID.randomUUID();
        identityRoleRepository.assignRole(identityId, role1.id());
        identityRoleRepository.assignRole(identityId, role2.id());

        identityRoleRepository.removeAllRolesForIdentity(identityId);

        assertThat(identityRoleRepository.findRoleIdsByIdentityId(identityId)).isEmpty();
    }

    @Test
    void returnsEmptyListWhenIdentityHasNoRoles() {
        assertThat(identityRoleRepository.findRoleIdsByIdentityId(UUID.randomUUID())).isEmpty();
    }
}
