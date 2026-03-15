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

import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.spi.PermissionsResolver;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A {@link PermissionsResolver} that resolves permissions by looking up the roles assigned
 * to an identity and returning the union of all permissions granted to those roles.
 */
@Component
public class RbacPermissionsResolver implements PermissionsResolver {

    private final IdentityRoleRepository identityRoleRepository;
    private final RolePermissionRepository rolePermissionRepository;

    RbacPermissionsResolver(IdentityRoleRepository identityRoleRepository,
                            RolePermissionRepository rolePermissionRepository) {
        this.identityRoleRepository = identityRoleRepository;
        this.rolePermissionRepository = rolePermissionRepository;
    }

    @Override
    public Set<String> resolve(Identity identity) {
        List<UUID> roleIds = identityRoleRepository.findRoleIdsByIdentityId(identity.id());
        if (roleIds.isEmpty()) {
            return Set.of();
        }
        return rolePermissionRepository.findPermissionsByRoleIds(roleIds);
    }
}
