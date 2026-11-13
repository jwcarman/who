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

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.jwcarman.who.core.event.WhoEvent;

/**
 * A permission was revoked from a role.
 *
 * @param occurredAt when it happened
 * @param roleId the role concerned
 * @param roleName the role's name
 * @param permission the permission concerned
 */
public record RolePermissionRemoved(
    Instant occurredAt, UUID roleId, String roleName, String permission) implements WhoEvent {

  /** Validates that no component is null. */
  public RolePermissionRemoved {
    Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    Objects.requireNonNull(roleId, "roleId must not be null");
    Objects.requireNonNull(roleName, "roleName must not be null");
    Objects.requireNonNull(permission, "permission must not be null");
  }
}
