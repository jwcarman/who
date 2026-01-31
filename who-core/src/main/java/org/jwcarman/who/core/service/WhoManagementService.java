package org.jwcarman.who.core.service;

import java.util.UUID;

/**
 * Service for managing users, roles, and identities.
 */
public interface WhoManagementService {

    // External identity management
    void linkExternalIdentity(UUID userId, String issuer, String subject);
    void unlinkExternalIdentity(UUID userId, UUID externalIdentityId);

    // Role management
    UUID createRole(String roleName);
    void deleteRole(UUID roleId);
    void assignRoleToUser(UUID userId, UUID roleId);
    void removeRoleFromUser(UUID userId, UUID roleId);

    // Permission management for roles
    void addPermissionToRole(UUID roleId, String permission);
    void removePermissionFromRole(UUID roleId, String permission);
}
