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
package org.jwcarman.who.passkey;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * One of the caller's passkeys, as {@code GET /who/me/passkeys} lists it. Named by its WebAuthn
 * credential id; who's internal id is never shown.
 *
 * @param credentialId the WebAuthn credential id, which removing it takes
 * @param label the name the person gave it
 * @param createdAt when it was registered, ISO-8601
 * @param lastUsedAt when it last signed in, ISO-8601; {@code null} if it never has
 */
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PasskeyView(
    String credentialId, String label, String createdAt, @Nullable String lastUsedAt) {}
