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

/** What became of a request to add or change an email address. */
public enum EmailAddressRequestOutcome {

  /** The request was saved and the address was sent a link and a code. */
  SENT,

  /** The request carried nothing usable as an address; nothing was sent. */
  INVALID_ADDRESS,

  /** The address is registered already, to this identity or another; nothing was sent. */
  ADDRESS_TAKEN,

  /**
   * A request by this identity, or for this address, was made within {@code
   * who.email-link.resend-interval}; nothing was sent.
   */
  RESEND_SUPPRESSED,

  /** Saving or sending failed; nothing was kept. */
  SEND_FAILED
}
