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

import static java.util.Objects.requireNonNull;

import org.jwcarman.who.core.email.WhoEmail;

/**
 * An email telling an identity's owner that a passkey was added to their account, so one they did
 * not add can be noticed and removed.
 *
 * @param address the owner's address, and the recipient
 * @param label the passkey's name, as its owner gave it
 */
public record PasskeyAddedMessage(String address, String label) implements WhoEmail {

  /** Compact constructor: validates that no field is {@code null}. */
  public PasskeyAddedMessage {
    requireNonNull(address, "address must not be null");
    requireNonNull(label, "label must not be null");
  }

  /**
   * Returns {@code passkey-added}, the name of this email's template.
   *
   * @return the template's name
   */
  @Override
  public String template() {
    return "passkey-added";
  }
}
