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
package org.jwcarman.who.core.email;

import java.util.Locale;

/** Turns a {@link WhoEmail} into an {@link OutgoingEmail}: its subject, text and optional HTML. */
@FunctionalInterface
public interface WhoEmailRenderer {

  /**
   * Renders the email in the locale.
   *
   * @param email the email to render
   * @param locale the language to render it in
   * @return the rendered email
   * @throws RuntimeException if the email's template is missing or fails to render
   */
  OutgoingEmail render(WhoEmail email, Locale locale);
}
