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

import static java.util.Objects.requireNonNull;

import java.time.Duration;

/**
 * Settings for email sign-in links.
 *
 * @param linkBaseUrl the page that receives the link; the token is appended as {@code #token=...}
 * @param tokenTtl how long a link stays valid
 * @param resendInterval the minimum time between two links for the same address
 * @param codeLength the number of characters in the typed sign-in code
 * @param codeMaxAttempts how many wrong guesses a code survives
 * @param codeSecret the secret keying the code hash
 */
public record EmailLinkSettings(
    String linkBaseUrl,
    Duration tokenTtl,
    Duration resendInterval,
    int codeLength,
    int codeMaxAttempts,
    String codeSecret) {

  private static final int MIN_CODE_LENGTH = 6;

  /**
   * Compact constructor: validates that no field is {@code null}, {@code codeLength} is at least 6,
   * {@code codeMaxAttempts} is at least 1 and {@code codeSecret} is not blank.
   */
  public EmailLinkSettings {
    requireNonNull(linkBaseUrl, "linkBaseUrl must not be null");
    requireNonNull(tokenTtl, "tokenTtl must not be null");
    requireNonNull(resendInterval, "resendInterval must not be null");
    requireNonNull(codeSecret, "codeSecret must not be null");
    if (codeLength < MIN_CODE_LENGTH) {
      throw new IllegalArgumentException("codeLength must be at least " + MIN_CODE_LENGTH);
    }
    if (codeMaxAttempts < 1) {
      throw new IllegalArgumentException("codeMaxAttempts must be at least 1");
    }
    if (codeSecret.isBlank()) {
      throw new IllegalArgumentException("codeSecret must not be blank");
    }
  }

  @Override
  public String toString() {
    return "EmailLinkSettings[linkBaseUrl="
        + linkBaseUrl
        + ", tokenTtl="
        + tokenTtl
        + ", resendInterval="
        + resendInterval
        + ", codeLength="
        + codeLength
        + ", codeMaxAttempts="
        + codeMaxAttempts
        + ", codeSecret=****]";
  }
}
