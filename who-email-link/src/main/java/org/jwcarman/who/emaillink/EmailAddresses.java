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

/** Checks an address typed into a sign-in request by its shape alone, before it reaches the DB. */
final class EmailAddresses {

  /** The longest an email address can be. */
  static final int MAX_LENGTH = 320;

  private EmailAddresses() {
    // utility class
  }

  /**
   * Whether the address may be looked up: not null or blank, no longer than an email address can
   * be, and free of control characters such as NUL, which PostgreSQL rejects.
   *
   * @param address the address as typed
   * @return {@code true} if the address may be passed to the database
   */
  static boolean isSafe(String address) {
    return address != null
        && !address.isBlank()
        && address.length() <= MAX_LENGTH
        && address.chars().noneMatch(Character::isISOControl);
  }
}
