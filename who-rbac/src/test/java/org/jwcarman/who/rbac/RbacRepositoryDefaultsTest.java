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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The default methods an RBAC repository implementation gets if it does not override them. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class RbacRepositoryDefaultsTest {

  private static final UUID IDENTITY = UUID.randomUUID();
  private static final UUID ROLE = UUID.randomUUID();
  private static final String PERMISSION = "task.read";

  @Nested
  class IdentityRoles {

    private final IdentityRoleRepository repository = mock(IdentityRoleRepository.class);

    IdentityRoles() {
      when(repository.assignRoleIfAbsent(any(), any())).thenCallRealMethod();
      when(repository.removeRoleIfPresent(any(), any())).thenCallRealMethod();
    }

    @Test
    void assigning_a_new_role_assigns_it_and_reports_a_change() {
      when(repository.findRoleIdsByIdentityId(IDENTITY)).thenReturn(List.of());

      assertThat(repository.assignRoleIfAbsent(IDENTITY, ROLE)).isTrue();
      verify(repository).assignRole(IDENTITY, ROLE);
    }

    @Test
    void assigning_a_role_already_held_changes_nothing() {
      when(repository.findRoleIdsByIdentityId(IDENTITY)).thenReturn(List.of(ROLE));

      assertThat(repository.assignRoleIfAbsent(IDENTITY, ROLE)).isFalse();
      verify(repository, never()).assignRole(any(), any());
    }

    @Test
    void removing_a_held_role_removes_it_and_reports_a_change() {
      when(repository.findRoleIdsByIdentityId(IDENTITY)).thenReturn(List.of(ROLE));

      assertThat(repository.removeRoleIfPresent(IDENTITY, ROLE)).isTrue();
      verify(repository).removeRole(IDENTITY, ROLE);
    }

    @Test
    void removing_a_role_not_held_changes_nothing() {
      when(repository.findRoleIdsByIdentityId(IDENTITY)).thenReturn(List.of());

      assertThat(repository.removeRoleIfPresent(IDENTITY, ROLE)).isFalse();
      verify(repository, never()).removeRole(any(), any());
    }
  }

  @Nested
  class RolePermissions {

    private final RolePermissionRepository repository = mock(RolePermissionRepository.class);

    RolePermissions() {
      when(repository.addPermissionIfAbsent(any(), anyString())).thenCallRealMethod();
      when(repository.removePermissionIfPresent(any(), anyString())).thenCallRealMethod();
    }

    @Test
    void adding_a_new_permission_adds_it_and_reports_a_change() {
      when(repository.findPermissionsByRoleId(ROLE)).thenReturn(Set.of());

      assertThat(repository.addPermissionIfAbsent(ROLE, PERMISSION)).isTrue();
      verify(repository).addPermission(ROLE, PERMISSION);
    }

    @Test
    void adding_a_permission_already_granted_changes_nothing() {
      when(repository.findPermissionsByRoleId(ROLE)).thenReturn(Set.of(PERMISSION));

      assertThat(repository.addPermissionIfAbsent(ROLE, PERMISSION)).isFalse();
      verify(repository, never()).addPermission(any(), anyString());
    }

    @Test
    void removing_a_granted_permission_removes_it_and_reports_a_change() {
      when(repository.findPermissionsByRoleId(ROLE)).thenReturn(Set.of(PERMISSION));

      assertThat(repository.removePermissionIfPresent(ROLE, PERMISSION)).isTrue();
      verify(repository).removePermission(ROLE, PERMISSION);
    }

    @Test
    void removing_a_permission_not_granted_changes_nothing() {
      when(repository.findPermissionsByRoleId(ROLE)).thenReturn(Set.of());

      assertThat(repository.removePermissionIfPresent(ROLE, PERMISSION)).isFalse();
      verify(repository, never()).removePermission(any(), anyString());
    }
  }

  @Nested
  class Roles {

    private final RoleRepository repository = mock(RoleRepository.class);

    Roles() {
      when(repository.deleteByIdIfPresent(any())).thenCallRealMethod();
    }

    @Test
    void deleting_an_existing_role_deletes_it_and_reports_a_change() {
      when(repository.existsById(ROLE)).thenReturn(true);

      assertThat(repository.deleteByIdIfPresent(ROLE)).isTrue();
      verify(repository).deleteById(ROLE);
    }

    @Test
    void deleting_a_missing_role_changes_nothing() {
      when(repository.existsById(ROLE)).thenReturn(false);

      assertThat(repository.deleteByIdIfPresent(ROLE)).isFalse();
      verify(repository, never()).deleteById(any());
    }
  }
}
