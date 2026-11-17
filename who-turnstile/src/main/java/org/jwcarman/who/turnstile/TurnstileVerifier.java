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

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.jwcarman.who.spring.security.HumanCheckRequest;

/**
 * Decides whether Cloudflare's answer about a token passes a request. {@link TurnstileHumanCheck}
 * asks Cloudflare; its verifier judges what Cloudflare said. {@link #strict(Set)} is who's default.
 * An application can define its own as a bean to add rules; it should start from {@code strict},
 * since a looser verifier weakens the check.
 */
@FunctionalInterface
public interface TurnstileVerifier {

  /**
   * Whether the answer passes the request.
   *
   * @param answer what Cloudflare said about the request's token
   * @param request the request being checked
   * @return true to let the request through
   */
  boolean accepts(TurnstileAnswer answer, HumanCheckRequest request);

  /**
   * Passes only a success made for the request's action and, when hostnames are given, on one of
   * them. Hostnames compare ignoring case and surrounding space.
   *
   * @param hostnames the hostnames a token may be made on; empty for any
   * @return the verifier
   */
  static TurnstileVerifier strict(Set<String> hostnames) {
    Set<String> allowed =
        requireNonNull(hostnames, "hostnames must not be null").stream()
            .map(TurnstileVerifier::normalized)
            .collect(Collectors.toUnmodifiableSet());
    return (answer, request) ->
        answer.success()
            && answer.action().filter(request.action()::equals).isPresent()
            && (allowed.isEmpty()
                || answer
                    .hostname()
                    .map(TurnstileVerifier::normalized)
                    .filter(allowed::contains)
                    .isPresent());
  }

  /**
   * Passes any success, whatever its action or hostname. Only for Cloudflare's test keys, whose
   * answers carry no action and always the hostname {@code example.com}.
   *
   * @return the verifier
   */
  static TurnstileVerifier successOnly() {
    return (answer, request) -> answer.success();
  }

  /**
   * Whether a secret key is one of Cloudflare's published test keys, which pass, fail or answer
   * "already spent" for any dummy token and never accept a real one. Matched exactly.
   *
   * @param secretKey a widget's secret key
   * @return true for a Cloudflare test key
   */
  static boolean isTestSecret(String secretKey) {
    return Set.of(
            "1x0000000000000000000000000000000AA",
            "2x0000000000000000000000000000000AA",
            "3x0000000000000000000000000000000AA")
        .contains(secretKey);
  }

  private static String normalized(String hostname) {
    return hostname.strip().toLowerCase(Locale.ROOT);
  }
}
