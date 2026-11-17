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

/**
 * An email who asks a {@link WhoEmailSender} to deliver. Sealed, so a sender can switch over every
 * kind and the compiler tells it when who adds one.
 */
public sealed interface WhoEmail
    permits EmailLinkMessage,
        InvitationMessage,
        EmailAddressConfirmationMessage,
        EmailAddressChangedMessage {

  /**
   * Returns the recipient.
   *
   * @return the address the email goes to
   */
  String address();
}
