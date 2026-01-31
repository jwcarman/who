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
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST controller for managing roles and identities.
 */
@RestController
@RequestMapping("/api/who/management")
public class WhoManagementController {

    private final WhoManagementService managementService;

    public WhoManagementController(WhoManagementService managementService) {
        this.managementService = managementService;
    }

    @PostMapping("/roles")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('who.role.create')")
    public RoleResponse createRole(@RequestBody CreateRoleRequest request) {
        UUID roleId = managementService.createRole(request.roleName());
        return new RoleResponse(roleId, request.roleName());
    }

    @DeleteMapping("/roles/{roleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('who.role.delete')")
    public void deleteRole(@PathVariable UUID roleId) {
        managementService.deleteRole(roleId);
    }

    @PostMapping("/users/{userId}/roles/{roleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('who.user.role.assign')")
    public void assignRoleToUser(@PathVariable UUID userId, @PathVariable UUID roleId) {
        managementService.assignRoleToUser(userId, roleId);
    }

    @DeleteMapping("/users/{userId}/roles/{roleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('who.user.role.remove')")
    public void removeRoleFromUser(@PathVariable UUID userId, @PathVariable UUID roleId) {
        managementService.removeRoleFromUser(userId, roleId);
    }

    @PostMapping("/roles/{roleId}/permissions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('who.role.permission.add')")
    public void addPermissionToRole(@PathVariable UUID roleId, @RequestBody AddPermissionRequest request) {
        managementService.addPermissionToRole(roleId, request.permission());
    }

    @DeleteMapping("/roles/{roleId}/permissions/{permission}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('who.role.permission.remove')")
    public void removePermissionFromRole(@PathVariable UUID roleId, @PathVariable String permission) {
        managementService.removePermissionFromRole(roleId, permission);
    }

    record CreateRoleRequest(String roleName) {}
    record RoleResponse(UUID roleId, String roleName) {}
    record AddPermissionRequest(String permission) {}
}
