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
package org.jwcarman.who.core.domain;

/**
 * Constants for Who library permissions.
 * These permissions control access to Who library management features.
 */
public final class WhoPermissions {

    // Invitation Management Permissions

    /**
     * Permission to create and send invitations to new users.
     */
    public static final String INVITATION_CREATE = "who.invitation.create";

    /**
     * Permission to revoke pending invitations.
     */
    public static final String INVITATION_REVOKE = "who.invitation.revoke";

    /**
     * Permission to list and view invitations.
     */
    public static final String INVITATION_LIST = "who.invitation.list";

    // Role Management Permissions

    /**
     * Permission to create new roles.
     */
    public static final String ROLE_CREATE = "who.role.create";

    /**
     * Permission to delete existing roles.
     */
    public static final String ROLE_DELETE = "who.role.delete";

    // User-Role Assignment Permissions

    /**
     * Permission to assign roles to users.
     */
    public static final String USER_ROLE_ASSIGN = "who.user.role.assign";

    /**
     * Permission to remove roles from users.
     */
    public static final String USER_ROLE_REMOVE = "who.user.role.remove";

    // Role-Permission Assignment Permissions

    /**
     * Permission to add permissions to roles.
     */
    public static final String ROLE_PERMISSION_ADD = "who.role.permission.add";

    /**
     * Permission to remove permissions from roles.
     */
    public static final String ROLE_PERMISSION_REMOVE = "who.role.permission.remove";

    private WhoPermissions() {
        // Prevent instantiation
    }
}
