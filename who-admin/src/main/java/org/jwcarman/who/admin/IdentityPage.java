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

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * One page of identities, as {@code GET /who/identities} answers it.
 *
 * @param items the identities on it, oldest first
 * @param page its zero-based number
 * @param size the most identities a page holds
 * @param total how many identities match, on every page
 */
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
@JsonInclude(JsonInclude.Include.ALWAYS)
public record IdentityPage(List<IdentityView> items, int page, int size, long total) {

  /**
   * Copies the items.
   *
   * @param items the identities on it, oldest first
   * @param page its zero-based number
   * @param size the most identities a page holds
   * @param total how many identities match, on every page
   */
  public IdentityPage {
    items = List.copyOf(items);
  }
}
