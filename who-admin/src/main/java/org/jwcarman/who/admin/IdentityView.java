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
package org.jwcarman.who.admin;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jwcarman.who.core.domain.IdentityStatus;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * An identity as who's admin endpoints show it.
 *
 * @param id the identity's id
 * @param status whether it may sign in
 * @param createdAt when it was created
 * @param roles the names of the roles it holds, sorted
 * @param emailAddresses its email addresses, sorted; empty without email sign-in
 * @param credentials how many sign-in credentials it holds, by kind ({@code email}, {@code
 *     passkey}, {@code account}); empty when who cannot count them
 */
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
@JsonInclude(JsonInclude.Include.ALWAYS)
public record IdentityView(
    UUID id,
    IdentityStatus status,
    @JsonFormat(shape = JsonFormat.Shape.STRING) Instant createdAt,
    List<String> roles,
    List<String> emailAddresses,
    Map<String, Long> credentials) {

  /**
   * Copies the lists and the map, keeping their order, so a view never changes after it is made.
   */
  public IdentityView {
    roles = List.copyOf(roles);
    emailAddresses = List.copyOf(emailAddresses);
    credentials = Collections.unmodifiableMap(new LinkedHashMap<>(credentials));
  }
}
