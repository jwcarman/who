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
package org.jwcarman.who.jpa.entity;

import org.jwcarman.who.jpa.entity.RolePermissionEntity.RolePermissionId;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RolePermissionEntityTest {

    @Test
    void compositeKeyShouldImplementEqualsCorrectly() {
        // Given
        UUID roleId = UUID.randomUUID();
        String permission = "read";

        RolePermissionId key1 = new RolePermissionId(roleId, permission);
        RolePermissionId key2 = new RolePermissionId(roleId, permission);
        RolePermissionId key3 = new RolePermissionId(UUID.randomUUID(), permission);

        // Then
        assertThat(key1)
            .isEqualTo(key2)
            .hasSameHashCodeAs(key2)
            .isNotEqualTo(key3);
    }

    @Test
    void compositeKeyShouldHandleNullValues() {
        // Given
        RolePermissionId key1 = new RolePermissionId(null, null);
        RolePermissionId key2 = new RolePermissionId(null, null);

        // Then
        assertThat(key1)
            .isEqualTo(key2)
            .hasSameHashCodeAs(key2);
    }

    @Test
    void entityShouldStoreRoleAndPermission() {
        // Given
        UUID roleId = UUID.randomUUID();
        String permission = "billing.invoice.read";

        RolePermissionEntity entity = new RolePermissionEntity();
        entity.setRoleId(roleId);
        entity.setPermission(permission);

        // Then
        assertThat(entity.getRoleId()).isEqualTo(roleId);
        assertThat(entity.getPermission()).isEqualTo(permission);
    }
}
