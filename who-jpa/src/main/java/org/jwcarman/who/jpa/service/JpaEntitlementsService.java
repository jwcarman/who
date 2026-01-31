package org.jwcarman.who.jpa.service;

import org.jwcarman.who.core.service.EntitlementsService;
import org.jwcarman.who.jpa.repository.RolePermissionRepository;
import org.jwcarman.who.jpa.repository.UserRoleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class JpaEntitlementsService implements EntitlementsService {

    private final UserRoleRepository userRoleRepository;
    private final RolePermissionRepository rolePermissionRepository;

    public JpaEntitlementsService(UserRoleRepository userRoleRepository,
                                  RolePermissionRepository rolePermissionRepository) {
        this.userRoleRepository = userRoleRepository;
        this.rolePermissionRepository = rolePermissionRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> resolvePermissions(UUID userId) {
        List<UUID> roleIds = userRoleRepository.findRoleIdsByUserId(userId);
        if (roleIds.isEmpty()) {
            return Set.of();
        }
        List<String> permissions = rolePermissionRepository.findPermissionsByRoleIds(roleIds);
        return new HashSet<>(permissions); // Deduplicate
    }
}
