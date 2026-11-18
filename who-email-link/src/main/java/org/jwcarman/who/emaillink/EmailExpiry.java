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

import java.time.Duration;

/** How a link email says how long its link and code work. */
final class EmailExpiry {

  private EmailExpiry() {}

  /**
   * The time left in whole minutes, rounded up and never less than one: {@code "15 minutes"},
   * {@code "1 minute"}.
   */
  static String describe(Duration validFor) {
    long wholeMinutes = validFor.toMinutes();
    long minutes =
        validFor.minusMinutes(wholeMinutes).isPositive() ? wholeMinutes + 1 : wholeMinutes;
    long shown = Math.max(1, minutes);
    return shown + (shown == 1 ? " minute" : " minutes");
  }
}
