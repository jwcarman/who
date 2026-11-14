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
package org.jwcarman.who.core.signup;

import static java.util.Objects.requireNonNull;

import java.util.function.Consumer;

import org.jwcarman.who.core.domain.Identity;

/** A {@link WhoSignUpPolicy}'s answer. */
public sealed interface SignUpDecision permits SignUpDecision.Allow, SignUpDecision.Refuse {

  /**
   * Allows the sign-up with nothing more to set up.
   *
   * @return the decision
   */
  static Allow allow() {
    return new Allow(identity -> {});
  }

  /**
   * Allows the sign-up, and sets the new identity up (roles, a profile) in the same transaction
   * that creates it: if {@code setup} throws, no identity or credential is left behind.
   *
   * @param setup runs once on the new identity
   * @return the decision
   */
  static Allow allow(Consumer<Identity> setup) {
    return new Allow(setup);
  }

  /**
   * Refuses the sign-up.
   *
   * @return the decision
   */
  static Refuse refuse() {
    return new Refuse();
  }

  /**
   * Create the identity.
   *
   * @param setup runs once on the new identity, in the transaction that creates it
   */
  record Allow(Consumer<Identity> setup) implements SignUpDecision {

    /** Validates that the setup is not null. */
    public Allow {
      requireNonNull(setup, "setup must not be null");
    }
  }

  /** Create nothing. */
  record Refuse() implements SignUpDecision {}
}
