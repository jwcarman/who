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

import static java.util.Objects.requireNonNull;

import org.jwcarman.who.core.email.WhoEmail;

/**
 * An email telling an address that it no longer signs in, because the identity's owner (or someone
 * with their session) made another address theirs in its place. The old address is the one most
 * likely to belong to the real owner if the account was taken over.
 *
 * @param address the old address, and the recipient
 * @param newAddress the address that replaced it
 */
public record EmailAddressChangedMessage(String address, String newAddress) implements WhoEmail {

  /** Compact constructor: validates that no field is {@code null}. */
  public EmailAddressChangedMessage {
    requireNonNull(address, "address must not be null");
    requireNonNull(newAddress, "newAddress must not be null");
  }

  /**
   * Returns the new address with all but the first character of its local part hidden, for example
   * {@code n***@example.com}, so the old address learns where the account went without being handed
   * the whole address.
   *
   * @return the masked new address
   */
  public String maskedNewAddress() {
    int at = newAddress.lastIndexOf('@');
    return newAddress.charAt(0) + "***" + (at > 0 ? newAddress.substring(at) : "");
  }

  /**
   * Returns {@code address-changed}, the name of this email's template.
   *
   * @return the template's name
   */
  @Override
  public String template() {
    return "address-changed";
  }
}
