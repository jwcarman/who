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
package org.jwcarman.who.email;

import java.util.Map;

import org.jwcarman.who.core.email.WhoEmail;

/**
 * Adds an application's own values to an email's template model, under the name {@code extra}: for
 * example the recipient's display name, as {@code {{extra.displayName}}}. Register one or more as
 * beans; their maps are merged in order, and a later contributor's key wins.
 */
@FunctionalInterface
public interface WhoEmailModelContributor {

  /**
   * The values to add for this email.
   *
   * @param email the email being rendered
   * @return the values, by name; empty for none
   */
  Map<String, Object> extra(WhoEmail email);
}
