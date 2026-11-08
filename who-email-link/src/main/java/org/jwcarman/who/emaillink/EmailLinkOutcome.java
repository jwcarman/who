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

/** What {@link EmailLinkIssuer#issue} did with a request. */
public enum EmailLinkOutcome {

  /** The link was stored and handed to the {@link EmailLinkSender}. */
  SENT,

  /** The text is not an address, or no email credential has this address. */
  UNKNOWN_ADDRESS,

  /** The address is known, but its identity is not active, so no link was issued. */
  INACTIVE_IDENTITY,

  /** A link was issued within the resend interval, so none was sent now. */
  RESEND_SUPPRESSED,

  /**
   * The link was not sent because a step failed: the lookup, the token store or the sender. A token
   * stored for a failed send is removed.
   */
  SEND_FAILED
}
