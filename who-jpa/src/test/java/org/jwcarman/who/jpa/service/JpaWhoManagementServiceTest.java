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
import org.jwcarman.who.jpa.entity.ExternalIdentityEntity;
import org.jwcarman.who.jpa.entity.RoleEntity;
import org.jwcarman.who.jpa.repository.*;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JpaWhoManagementServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private UserRoleRepository userRoleRepository;

    @Mock
    private RolePermissionRepository rolePermissionRepository;

    @Mock
    private ExternalIdentityRepository externalIdentityRepository;

    @InjectMocks
    private JpaWhoManagementService service;

    @Test
    void createRole_withNewRole_createsAndReturnsRoleId() {
        // Given
        String roleName = "ADMIN";
        RoleEntity role = new RoleEntity();
        role.setId(UUID.randomUUID());
        role.setName(roleName);

        when(roleRepository.findByName(roleName)).thenReturn(Optional.empty());
        when(roleRepository.save(any(RoleEntity.class))).thenReturn(role);

        // When
        UUID roleId = service.createRole(roleName);

        // Then
        assertThat(roleId).isEqualTo(role.getId());
        verify(roleRepository).findByName(roleName);
        verify(roleRepository).save(any(RoleEntity.class));
    }

    @Test
    void createRole_withExistingRole_throwsException() {
        // Given
        String roleName = "ADMIN";
        RoleEntity existingRole = new RoleEntity();
        existingRole.setName(roleName);

        when(roleRepository.findByName(roleName)).thenReturn(Optional.of(existingRole));

        // When/Then
        assertThatThrownBy(() -> service.createRole(roleName))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Role already exists");

        verify(roleRepository).findByName(roleName);
        verify(roleRepository, never()).save(any());
    }

    @Test
    void deleteRole_withExistingRole_deletesRoleAndAssociations() {
        // Given
        UUID roleId = UUID.randomUUID();
        when(roleRepository.existsById(roleId)).thenReturn(true);

        // When
        service.deleteRole(roleId);

        // Then
        verify(roleRepository).existsById(roleId);
        verify(rolePermissionRepository).deleteByRoleId(roleId);
        verify(userRoleRepository).deleteByRoleId(roleId);
        verify(roleRepository).deleteById(roleId);
    }

    @Test
    void deleteRole_withNonExistentRole_throwsException() {
        // Given
        UUID roleId = UUID.randomUUID();
        when(roleRepository.existsById(roleId)).thenReturn(false);

        // When/Then
        assertThatThrownBy(() -> service.deleteRole(roleId))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Role does not exist");

        verify(roleRepository).existsById(roleId);
        verify(rolePermissionRepository, never()).deleteByRoleId(any());
        verify(userRoleRepository, never()).deleteByRoleId(any());
        verify(roleRepository, never()).deleteById(any());
    }

    @Test
    void assignRoleToUser_withValidUserAndRole_createsAssignment() {
        // Given
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();

        when(userRepository.existsById(userId)).thenReturn(true);
        when(roleRepository.existsById(roleId)).thenReturn(true);

        // When
        service.assignRoleToUser(userId, roleId);

        // Then
        verify(userRepository).existsById(userId);
        verify(roleRepository).existsById(roleId);
        verify(userRoleRepository).save(any());
    }

    @Test
    void assignRoleToUser_withInvalidUser_throwsException() {
        // Given
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();

        when(userRepository.existsById(userId)).thenReturn(false);

        // When/Then
        assertThatThrownBy(() -> service.assignRoleToUser(userId, roleId))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("User does not exist");

        verify(userRepository).existsById(userId);
        verify(userRoleRepository, never()).save(any());
    }

    @Test
    void linkExternalIdentity_withNewIdentity_createsLink() {
        // Given
        UUID userId = UUID.randomUUID();
        String issuer = "https://accounts.google.com";
        String subject = "123456";

        when(userRepository.existsById(userId)).thenReturn(true);
        when(externalIdentityRepository.findByIssuerAndSubject(issuer, subject))
            .thenReturn(Optional.empty());

        // When
        service.linkExternalIdentity(userId, issuer, subject);

        // Then
        verify(userRepository).existsById(userId);
        verify(externalIdentityRepository, times(2)).findByIssuerAndSubject(issuer, subject);
        verify(externalIdentityRepository).save(any(ExternalIdentityEntity.class));
    }

    @Test
    void linkExternalIdentity_withExistingIdentityForDifferentUser_throwsException() {
        // Given
        UUID userId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        String issuer = "https://accounts.google.com";
        String subject = "123456";

        ExternalIdentityEntity existingIdentity = new ExternalIdentityEntity();
        existingIdentity.setUserId(otherUserId);
        existingIdentity.setIssuer(issuer);
        existingIdentity.setSubject(subject);

        when(userRepository.existsById(userId)).thenReturn(true);
        when(externalIdentityRepository.findByIssuerAndSubject(issuer, subject))
            .thenReturn(Optional.of(existingIdentity));

        // When/Then
        assertThatThrownBy(() -> service.linkExternalIdentity(userId, issuer, subject))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("already linked to another user");

        verify(userRepository).existsById(userId);
        verify(externalIdentityRepository).findByIssuerAndSubject(issuer, subject);
        verify(externalIdentityRepository, never()).save(any());
    }
}
