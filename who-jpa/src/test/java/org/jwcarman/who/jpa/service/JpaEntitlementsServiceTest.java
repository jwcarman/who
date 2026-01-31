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
package org.jwcarman.who.jpa.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.jwcarman.who.jpa.repository.RolePermissionRepository;
import org.jwcarman.who.jpa.repository.UserRoleRepository;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JpaEntitlementsServiceTest {

    @Mock
    private UserRoleRepository userRoleRepository;

    @Mock
    private RolePermissionRepository rolePermissionRepository;

    @InjectMocks
    private JpaEntitlementsService service;

    @Test
    void resolvePermissions_withMultipleRoles_returnsAllUniquePermissions() {
        // Given
        UUID userId = UUID.randomUUID();
        UUID role1 = UUID.randomUUID();
        UUID role2 = UUID.randomUUID();

        when(userRoleRepository.findRoleIdsByUserId(userId))
            .thenReturn(List.of(role1, role2));
        when(rolePermissionRepository.findPermissionsByRoleIds(List.of(role1, role2)))
            .thenReturn(List.of("read", "write", "read", "delete")); // duplicate "read"

        // When
        Set<String> permissions = service.resolvePermissions(userId);

        // Then
        assertThat(permissions).containsExactlyInAnyOrder("read", "write", "delete");
    }

    @Test
    void resolvePermissions_withNoRoles_returnsEmptySet() {
        // Given
        UUID userId = UUID.randomUUID();
        when(userRoleRepository.findRoleIdsByUserId(userId))
            .thenReturn(List.of());

        // When
        Set<String> permissions = service.resolvePermissions(userId);

        // Then
        assertThat(permissions).isEmpty();
    }
}
