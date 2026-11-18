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

import static java.util.Objects.requireNonNull;

import org.jspecify.annotations.Nullable;

/**
 * A library's email templates and subjects. A library that ships its own versions of who's emails
 * registers one as a bean, typically from its own auto-configuration. For each file, who searches
 * the application's locations ({@code who.email.templates}) first, then each library's in bean
 * order ({@code @Order}), then its own built-ins; subjects are found the same way, in the
 * application's messages, then each library's bundle, then who's.
 *
 * @param location where the library's templates are, as a Spring resource location such as {@code
 *     classpath:/com/acme/email/}
 * @param messages the basename of the library's message bundle, as {@code ResourceBundle} spells
 *     it, such as {@code com/acme/email-messages}, or {@code null} for none
 */
public record WhoEmailTemplates(String location, @Nullable String messages) {

  /** Checks the location. */
  public WhoEmailTemplates {
    requireNonNull(location, "location must not be null");
  }
}
