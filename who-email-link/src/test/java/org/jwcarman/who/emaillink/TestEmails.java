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
package org.jwcarman.who.emaillink;

import java.util.Locale;
import java.util.function.Consumer;

import org.jwcarman.who.core.email.OutgoingEmail;
import org.jwcarman.who.core.email.WhoEmail;
import org.jwcarman.who.core.email.WhoEmails;

/** A real {@link WhoEmails} for tests, handing each email's record to an outbox. */
final class TestEmails {

  private TestEmails() {}

  /**
   * The seam, rendering each email as its template's name and delivering its record to {@code
   * outbox}; a throwing outbox is a failed send.
   */
  static WhoEmails delivering(Consumer<WhoEmail> outbox) {
    return new WhoEmails(
        (email, locale) -> new OutgoingEmail(email.address(), email.template(), "", null, email),
        out -> outbox.accept(out.source()),
        () -> Locale.ENGLISH);
  }
}
