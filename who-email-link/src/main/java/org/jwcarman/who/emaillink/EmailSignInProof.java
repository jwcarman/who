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
 * What a sign-in request at {@code POST /login/ott} presents: the token from the emailed link, the
 * address and code typed by hand, or something malformed. The secret parts never appear in {@code
 * toString()}.
 */
public sealed interface EmailSignInProof {

  /**
   * The token from the emailed link.
   *
   * @param token the raw token value
   */
  record LinkToken(String token) implements EmailSignInProof {

    @Override
    public String toString() {
      return "LinkToken[token=****]";
    }
  }

  /**
   * An address and the code from the email, both exactly as typed.
   *
   * @param address the address, as typed
   * @param code the code, as typed
   */
  record EmailCode(String address, String code) implements EmailSignInProof {

    @Override
    public String toString() {
      return "EmailCode[address=" + address + ", code=****]";
    }
  }

  /** A request that is neither a link token nor an address and code. */
  record Malformed() implements EmailSignInProof {}
}
