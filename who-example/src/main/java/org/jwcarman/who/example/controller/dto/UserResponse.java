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
package org.jwcarman.who.example.controller.dto;

import org.jwcarman.who.core.domain.UserStatus;
import org.jwcarman.who.jpa.entity.UserEntity;

import java.time.Instant;
import java.util.UUID;

public record UserResponse(
    UUID id,
    UserStatus status,
    Instant createdAt,
    Instant updatedAt
) {
    public static UserResponse from(UserEntity user) {
        return new UserResponse(
            user.getId(),
            user.getStatus(),
            user.getCreatedAt(),
            user.getUpdatedAt()
        );
    }
}
