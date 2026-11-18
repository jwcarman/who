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

import static java.util.Objects.requireNonNull;

import org.jspecify.annotations.Nullable;

/**
 * A rendered email, ready for a {@link WhoEmailSender} to deliver.
 *
 * <p>Its {@link #toString()} leaves out the text and the HTML, which carry sign-in links and codes.
 *
 * @param to the recipient's address
 * @param subject the subject line
 * @param text the plain-text body, not HTML-escaped: send it as {@code text/plain}, never inside
 *     HTML
 * @param html the HTML body, or {@code null} when the email has no HTML template; the sender then
 *     sends text only
 * @param source the email this was rendered from, for senders that use a provider's own templates
 */
public record OutgoingEmail(
    String to, String subject, String text, @Nullable String html, WhoEmail source) {

  /** Checks the required parts. */
  public OutgoingEmail {
    requireNonNull(to, "to must not be null");
    requireNonNull(subject, "subject must not be null");
    requireNonNull(text, "text must not be null");
    requireNonNull(source, "source must not be null");
  }

  /** The recipient, the subject, the template and whether there is HTML; never the bodies. */
  @Override
  public String toString() {
    return "OutgoingEmail[to="
        + to
        + ", subject="
        + subject
        + ", template="
        + source.template()
        + ", html="
        + (html != null)
        + "]";
  }
}
