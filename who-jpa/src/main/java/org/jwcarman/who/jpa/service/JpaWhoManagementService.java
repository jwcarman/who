package org.jwcarman.who.jpa.service;

import org.jwcarman.who.core.service.WhoManagementService;
import org.jwcarman.who.jpa.entity.*;
import org.jwcarman.who.jpa.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
public class JpaWhoManagementService implements WhoManagementService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final ExternalIdentityRepository externalIdentityRepository;

    public JpaWhoManagementService(UserRepository userRepository,
                                   RoleRepository roleRepository,
                                   UserRoleRepository userRoleRepository,
                                   RolePermissionRepository rolePermissionRepository,
                                   ExternalIdentityRepository externalIdentityRepository) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.externalIdentityRepository = externalIdentityRepository;
    }

    @Override
    public void linkExternalIdentity(UUID userId, String issuer, String subject) {
        // Validate user exists
        if (!userRepository.existsById(userId)) {
            throw new IllegalArgumentException("User does not exist: " + userId);
        }

        // Check if identity is already linked to another user
        externalIdentityRepository.findByIssuerAndSubject(issuer, subject)
            .ifPresent(existing -> {
                if (!existing.getUserId().equals(userId)) {
                    throw new IllegalArgumentException(
                        "External identity is already linked to another user"
                    );
                }
            });

        // Create or update external identity
        ExternalIdentityEntity identity = externalIdentityRepository
            .findByIssuerAndSubject(issuer, subject)
            .orElseGet(() -> {
                ExternalIdentityEntity newIdentity = new ExternalIdentityEntity();
                newIdentity.setUserId(userId);
                newIdentity.setIssuer(issuer);
                newIdentity.setSubject(subject);
                return newIdentity;
            });

        externalIdentityRepository.save(identity);
    }

    @Override
    public void unlinkExternalIdentity(UUID userId, UUID externalIdentityId) {
        // Validate user exists
        if (!userRepository.existsById(userId)) {
            throw new IllegalArgumentException("User does not exist: " + userId);
        }

        // Validate external identity exists and is linked to the user
        ExternalIdentityEntity identity = externalIdentityRepository.findById(externalIdentityId)
            .orElseThrow(() -> new IllegalArgumentException(
                "External identity does not exist: " + externalIdentityId
            ));

        if (!identity.getUserId().equals(userId)) {
            throw new IllegalArgumentException(
                "External identity is not linked to user: " + userId
            );
        }

        externalIdentityRepository.delete(identity);
    }

    @Override
    public UUID createRole(String roleName) {
        // Check if role already exists
        if (roleRepository.findByName(roleName).isPresent()) {
            throw new IllegalArgumentException("Role already exists: " + roleName);
        }

        RoleEntity role = new RoleEntity();
        role.setName(roleName);
        role = roleRepository.save(role);
        return role.getId();
    }

    @Override
    public void deleteRole(UUID roleId) {
        // Validate role exists
        if (!roleRepository.existsById(roleId)) {
            throw new IllegalArgumentException("Role does not exist: " + roleId);
        }

        // Delete role permissions
        rolePermissionRepository.deleteByRoleId(roleId);

        // Delete user-role assignments
        userRoleRepository.deleteByRoleId(roleId);

        // Delete role
        roleRepository.deleteById(roleId);
    }

    @Override
    public void assignRoleToUser(UUID userId, UUID roleId) {
        // Validate user exists
        if (!userRepository.existsById(userId)) {
            throw new IllegalArgumentException("User does not exist: " + userId);
        }

        // Validate role exists
        if (!roleRepository.existsById(roleId)) {
            throw new IllegalArgumentException("Role does not exist: " + roleId);
        }

        // Create user-role assignment (will be ignored if already exists due to composite key)
        UserRoleEntity userRole = new UserRoleEntity();
        userRole.setUserId(userId);
        userRole.setRoleId(roleId);
        userRoleRepository.save(userRole);
    }

    @Override
    public void removeRoleFromUser(UUID userId, UUID roleId) {
        // Validate user exists
        if (!userRepository.existsById(userId)) {
            throw new IllegalArgumentException("User does not exist: " + userId);
        }

        // Validate role exists
        if (!roleRepository.existsById(roleId)) {
            throw new IllegalArgumentException("Role does not exist: " + roleId);
        }

        // Check if role is assigned to user
        boolean isAssigned = userRoleRepository.findRoleIdsByUserId(userId).contains(roleId);
        if (!isAssigned) {
            throw new IllegalArgumentException(
                "Role is not assigned to user: " + roleId
            );
        }

        // Remove role from user
        userRoleRepository.deleteByUserIdAndRoleId(userId, roleId);
    }

    @Override
    public void addPermissionToRole(UUID roleId, String permission) {
        // Validate role exists
        if (!roleRepository.existsById(roleId)) {
            throw new IllegalArgumentException("Role does not exist: " + roleId);
        }

        // Create role permission (will be ignored if already exists due to composite key)
        RolePermissionEntity rolePermission = new RolePermissionEntity();
        rolePermission.setRoleId(roleId);
        rolePermission.setPermission(permission);
        rolePermissionRepository.save(rolePermission);
    }

    @Override
    public void removePermissionFromRole(UUID roleId, String permission) {
        // Validate role exists
        if (!roleRepository.existsById(roleId)) {
            throw new IllegalArgumentException("Role does not exist: " + roleId);
        }

        // Check if permission is assigned to role
        boolean isAssigned = rolePermissionRepository.findByRoleId(roleId).stream()
            .anyMatch(rp -> rp.getPermission().equals(permission));
        if (!isAssigned) {
            throw new IllegalArgumentException(
                "Permission is not assigned to role: " + permission
            );
        }

        // Remove permission from role
        // Since we can't use deleteById with composite key, we need to find and delete
        rolePermissionRepository.findByRoleId(roleId).stream()
            .filter(rp -> rp.getPermission().equals(permission))
            .findFirst()
            .ifPresent(rolePermissionRepository::delete);
    }
}
