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
package org.jwcarman.who.web;

import org.jwcarman.who.core.service.WhoManagementService;
import org.jwcarman.who.web.WhoManagementController.AddPermissionRequest;
import org.jwcarman.who.web.WhoManagementController.CreateRoleRequest;
import org.jwcarman.who.web.WhoManagementController.RoleResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WhoManagementControllerTest {

    @Mock
    private WhoManagementService managementService;

    @InjectMocks
    private WhoManagementController controller;

    @Test
    void shouldCreateRole() {
        // Given
        UUID roleId = UUID.randomUUID();
        String roleName = "ADMIN";
        when(managementService.createRole(roleName)).thenReturn(roleId);

        // When
        RoleResponse response = controller.createRole(new CreateRoleRequest(roleName));

        // Then
        assertThat(response.roleId()).isEqualTo(roleId);
        assertThat(response.roleName()).isEqualTo(roleName);
        verify(managementService).createRole(roleName);
    }

    @Test
    void shouldDeleteRole() {
        // Given
        UUID roleId = UUID.randomUUID();

        // When
        controller.deleteRole(roleId);

        // Then
        verify(managementService).deleteRole(roleId);
    }

    @Test
    void shouldAssignRoleToUser() {
        // Given
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();

        // When
        controller.assignRoleToUser(userId, roleId);

        // Then
        verify(managementService).assignRoleToUser(userId, roleId);
    }

    @Test
    void shouldRemoveRoleFromUser() {
        // Given
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();

        // When
        controller.removeRoleFromUser(userId, roleId);

        // Then
        verify(managementService).removeRoleFromUser(userId, roleId);
    }

    @Test
    void shouldAddPermissionToRole() {
        // Given
        UUID roleId = UUID.randomUUID();
        String permission = "billing.invoice.read";

        // When
        controller.addPermissionToRole(roleId, new AddPermissionRequest(permission));

        // Then
        verify(managementService).addPermissionToRole(roleId, permission);
    }

    @Test
    void shouldRemovePermissionFromRole() {
        // Given
        UUID roleId = UUID.randomUUID();
        String permission = "billing.invoice.read";

        // When
        controller.removePermissionFromRole(roleId, permission);

        // Then
        verify(managementService).removePermissionFromRole(roleId, permission);
    }
}
