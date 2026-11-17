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
package org.jwcarman.who.turnstile;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Cloudflare's siteverify answer about a token.
 *
 * @param success whether Cloudflare accepts the token
 * @param action the action the widget was given when the token was made, if any
 * @param hostname the hostname the token was made on, if any
 * @param challengeTs when the widget's challenge was solved, if given
 * @param cdata the data the page gave the widget when it made the token, if any
 * @param errorCodes why Cloudflare refused the token, empty when it accepted it
 */
public record TurnstileAnswer(
    boolean success,
    Optional<String> action,
    Optional<String> hostname,
    Optional<Instant> challengeTs,
    Optional<String> cdata,
    List<String> errorCodes) {

  /** Rejects nulls, and copies the error codes. */
  public TurnstileAnswer {
    requireNonNull(action, "action must not be null");
    requireNonNull(hostname, "hostname must not be null");
    requireNonNull(challengeTs, "challengeTs must not be null");
    requireNonNull(cdata, "cdata must not be null");
    errorCodes = List.copyOf(requireNonNull(errorCodes, "errorCodes must not be null"));
  }

  /**
   * Reads siteverify's JSON. Only a JSON {@code true} is success; fields of the wrong type, and a
   * {@code challenge_ts} that is not an ISO-8601 instant, count as absent.
   *
   * @param json the answer, as Spring read it
   * @return the answer
   */
  public static TurnstileAnswer of(Map<String, Object> json) {
    return new TurnstileAnswer(
        Boolean.TRUE.equals(json.get("success")),
        text(json.get("action")),
        text(json.get("hostname")),
        text(json.get("challenge_ts")).flatMap(TurnstileAnswer::instant),
        text(json.get("cdata")),
        json.get("error-codes") instanceof List<?> codes
            ? codes.stream().filter(String.class::isInstance).map(String.class::cast).toList()
            : List.of());
  }

  private static Optional<Instant> instant(String text) {
    try {
      return Optional.of(Instant.parse(text));
    } catch (DateTimeParseException _) {
      return Optional.empty();
    }
  }

  private static Optional<String> text(Object value) {
    return value instanceof String text ? Optional.of(text) : Optional.empty();
  }
}
