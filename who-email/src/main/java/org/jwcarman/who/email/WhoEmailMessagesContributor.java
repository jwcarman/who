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

/**
 * Contributes a feature's bundle of default email messages, such as its subjects under {@code
 * who.email.<template>.subject}. Each feature that sends email registers one as a bean; the
 * application's own messages win over every bundle.
 *
 * @param basename the bundle's basename, as {@code ResourceBundle} spells it, such as {@code
 *     org/jwcarman/who/emaillink/email-messages}
 */
public record WhoEmailMessagesContributor(String basename) {

  /** Checks the basename. */
  public WhoEmailMessagesContributor {
    requireNonNull(basename, "basename must not be null");
  }
}
