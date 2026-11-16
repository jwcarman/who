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
package org.jwcarman.who.examples.observability;

/**
 * One kind of request the traffic generator makes, and how often it picks it relative to the
 * others.
 */
enum Scenario {
  /** Reads the greeting with a good key: a successful {@code api_key} sign-in. */
  GOOD_KEY(6),
  /** Writes the greeting with a key that only reads: signed in, then refused with 403. */
  MISSING_SCOPE(2),
  /** Calls with a key who never issued: {@code UNKNOWN_KEY}. */
  UNKNOWN_KEY(2),
  /** Makes a key, revokes it, then calls with it: {@code UNKNOWN_KEY}. */
  REVOKED_KEY(1),
  /** Calls with a key that has expired: {@code EXPIRED_KEY}. */
  EXPIRED_KEY(1),
  /** Asks for a code for a known address: sent, or suppressed within the resend interval. */
  CODE_FOR_KNOWN_ADDRESS(1),
  /** Asks for a code for an address nobody has: answered alike, and counted unknown. */
  CODE_FOR_UNKNOWN_ADDRESS(1),
  /** Signs in with a wrong code: {@code WRONG_CODE}, or {@code NO_LIVE_CODE} once used up. */
  WRONG_CODE(2),
  /** Asks for a code, reads it from Mailpit, signs in with it, and signs out. */
  CODE_SIGN_IN(2);

  private final int weight;

  Scenario(int weight) {
    this.weight = weight;
  }

  /**
   * How often the generator picks this scenario, relative to the others.
   *
   * @return the weight
   */
  int weight() {
    return weight;
  }
}
