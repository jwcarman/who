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

/** Delivers a sign-in link to an email address. */
@FunctionalInterface
public interface EmailLinkSender {

  /**
   * Sends the link.
   *
   * @param address the recipient address
   * @param link the sign-in link, carrying the token in its fragment
   */
  void send(String address, String link);
}
