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

import org.jwcarman.who.core.service.RbacService;
import org.jwcarman.who.core.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller for managing roles and identities.
 * <p>
 * This controller provides administrative endpoints for role-based access control (RBAC) operations,
 * including creating/deleting roles, assigning roles to users, and managing role permissions.
 * All endpoints require appropriate permissions as specified by {@code @PreAuthorize} annotations.
 * <p>
 * The base path is configurable via {@code who.web.mount-point} property (defaults to {@code /api/who}).
 */
@RestController
@RequestMapping("${who.web.mount-point:/api/who}/management")
public class WhoManagementController {

    private final RbacService rbacService;
    private final UserService userService;

    /**
     * Constructs a new WhoManagementController with required services.
     *
     * @param rbacService the RBAC service for role and permission operations
     * @param userService the user service for user-role assignments
     */
    public WhoManagementController(RbacService rbacService, UserService userService) {
        this.rbacService = rbacService;
        this.userService = userService;
    }

    /**
     * Creates a new role.
     * <p>
     * HTTP POST to {@code /management/roles}
     * <p>
     * Requires permission: {@code who.role.create}
     *
     * @param request the role creation request containing the role name
     * @return the created role with generated ID
     */
    @PostMapping("/roles")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('who.role.create')")
    public RoleResponse createRole(@RequestBody CreateRoleRequest request) {
        UUID roleId = rbacService.createRole(request.roleName());
        return new RoleResponse(roleId, request.roleName());
    }

    /**
     * Deletes an existing role.
     * <p>
     * HTTP DELETE to {@code /management/roles/{roleId}}
     * <p>
     * Requires permission: {@code who.role.delete}
     *
     * @param roleId the ID of the role to delete
     */
    @DeleteMapping("/roles/{roleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('who.role.delete')")
    public void deleteRole(@PathVariable UUID roleId) {
        rbacService.deleteRole(roleId);
    }

    /**
     * Assigns a role to a user.
     * <p>
     * HTTP POST to {@code /management/users/{userId}/roles/{roleId}}
     * <p>
     * Requires permission: {@code who.user.role.assign}
     *
     * @param userId the ID of the user
     * @param roleId the ID of the role to assign
     */
    @PostMapping("/users/{userId}/roles/{roleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('who.user.role.assign')")
    public void assignRoleToUser(@PathVariable UUID userId, @PathVariable UUID roleId) {
        userService.assignRoleToUser(userId, roleId);
    }

    /**
     * Removes a role from a user.
     * <p>
     * HTTP DELETE to {@code /management/users/{userId}/roles/{roleId}}
     * <p>
     * Requires permission: {@code who.user.role.remove}
     *
     * @param userId the ID of the user
     * @param roleId the ID of the role to remove
     */
    @DeleteMapping("/users/{userId}/roles/{roleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('who.user.role.remove')")
    public void removeRoleFromUser(@PathVariable UUID userId, @PathVariable UUID roleId) {
        userService.removeRoleFromUser(userId, roleId);
    }

    /**
     * Adds a permission to a role.
     * <p>
     * HTTP POST to {@code /management/roles/{roleId}/permissions}
     * <p>
     * Requires permission: {@code who.role.permission.add}
     *
     * @param roleId the ID of the role
     * @param request the request containing the permission string to add
     */
    @PostMapping("/roles/{roleId}/permissions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('who.role.permission.add')")
    public void addPermissionToRole(@PathVariable UUID roleId, @RequestBody AddPermissionRequest request) {
        rbacService.addPermissionToRole(roleId, request.permission());
    }

    /**
     * Removes a permission from a role.
     * <p>
     * HTTP DELETE to {@code /management/roles/{roleId}/permissions/{permission}}
     * <p>
     * Requires permission: {@code who.role.permission.remove}
     *
     * @param roleId the ID of the role
     * @param permission the permission string to remove
     */
    @DeleteMapping("/roles/{roleId}/permissions/{permission}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('who.role.permission.remove')")
    public void removePermissionFromRole(@PathVariable UUID roleId, @PathVariable String permission) {
        rbacService.removePermissionFromRole(roleId, permission);
    }

    /**
     * Request to create a new role.
     *
     * @param roleName the name of the role to create
     */
    record CreateRoleRequest(String roleName) {}

    /**
     * Response containing role information.
     *
     * @param roleId the unique identifier of the role
     * @param roleName the name of the role
     */
    record RoleResponse(UUID roleId, String roleName) {}

    /**
     * Request to add a permission to a role.
     *
     * @param permission the permission string to add
     */
    record AddPermissionRequest(String permission) {}
}
