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
package org.jwcarman.who.core.event;

/** Why a sign-in attempt failed. */
public enum SignInFailureReason {
  /** The presented credential could not be parsed. */
  MALFORMED,
  /** The email link was invalid or expired. */
  INVALID_LINK,
  /** No live one-time code exists for the address. */
  NO_LIVE_CODE,
  /** The one-time code did not match. */
  WRONG_CODE,
  /** The one-time code was already consumed. */
  CODE_ALREADY_USED,
  /** The passkey assertion failed verification. */
  INVALID_ASSERTION,
  /** The API key is unknown. */
  UNKNOWN_KEY,
  /** The token lacked required claims. */
  MISSING_CLAIMS,
  /** The credential is not linked to any identity. */
  UNKNOWN_CREDENTIAL,
  /** The identity is inactive. */
  INACTIVE_IDENTITY
}
