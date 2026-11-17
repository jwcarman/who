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

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jwcarman.who.spring.security.HumanCheckRequest;
import org.jwcarman.who.spring.security.WhoHumanCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Cloudflare Turnstile as who's human check. The page runs Turnstile's widget with the action of
 * the endpoint it calls ({@code email-send} or {@code email-sign-in}) and sends the token it gets
 * in the {@value WhoHumanCheck#HEADER} header. This check asks Cloudflare's siteverify API about
 * the token and hands the answer to its {@link TurnstileVerifier}, which decides; {@link
 * TurnstileVerifier#strict(Set)} passes only a success made for the same action and, when hostnames
 * are listed, on one of them. Cloudflare lets a token be verified once.
 *
 * <p>A request without a token fails without calling Cloudflare. When Cloudflare cannot be reached,
 * or answers an error, the request fails unless this check was made to fail open; a token
 * Cloudflare refuses always fails.
 */
public final class TurnstileHumanCheck implements WhoHumanCheck {

  /** Cloudflare's siteverify endpoint, the default verify URL. */
  public static final String DEFAULT_VERIFY_URL =
      "https://challenges.cloudflare.com/turnstile/v0/siteverify";

  private static final Logger log = LoggerFactory.getLogger(TurnstileHumanCheck.class);

  private static final ParameterizedTypeReference<Map<String, Object>> ANSWER =
      new ParameterizedTypeReference<>() {};

  private final RestClient restClient;
  private final URI verifyUrl;
  private final String secretKey;
  private final TurnstileVerifier verifier;
  private final boolean failOpen;

  /**
   * Creates the check.
   *
   * @param restClient calls Cloudflare; its timeouts bound how long a request waits
   * @param verifyUrl the siteverify endpoint, normally {@link #DEFAULT_VERIFY_URL}; another for a
   *     stand-in in tests
   * @param secretKey the widget's secret key
   * @param verifier decides whether Cloudflare's answer passes a request
   * @param failOpen true to let requests through when Cloudflare cannot be reached or answers an
   *     error
   */
  public TurnstileHumanCheck(
      RestClient restClient,
      URI verifyUrl,
      String secretKey,
      TurnstileVerifier verifier,
      boolean failOpen) {
    this.restClient = requireNonNull(restClient, "restClient must not be null");
    this.verifyUrl = requireNonNull(verifyUrl, "verifyUrl must not be null");
    this.secretKey = requireNonNull(secretKey, "secretKey must not be null");
    this.verifier = requireNonNull(verifier, "verifier must not be null");
    this.failOpen = failOpen;
  }

  @Override
  public boolean passes(HumanCheckRequest request) {
    if (request.token().isEmpty()) {
      return false;
    }
    Optional<Map<String, Object>> answer = verify(request);
    if (answer.isEmpty()) {
      return failOpen;
    }
    return verifier.accepts(TurnstileAnswer.of(answer.get()), request);
  }

  /** Cloudflare's answer, or empty when it could not be had. */
  private Optional<Map<String, Object>> verify(HumanCheckRequest request) {
    String form =
        field("secret", secretKey)
            + "&"
            + field("response", request.token().orElseThrow())
            + "&"
            + field("remoteip", request.clientIp());
    try {
      return Optional.ofNullable(
          restClient
              .post()
              .uri(verifyUrl)
              .contentType(MediaType.APPLICATION_FORM_URLENCODED)
              // written as raw bytes: a form map would pass through Spring's message converters,
              // which log the body, secret and token included, at DEBUG
              .body(out -> out.write(form.getBytes(StandardCharsets.UTF_8)))
              .retrieve()
              .body(ANSWER));
    } catch (RestClientException e) {
      log.warn("Cloudflare Turnstile could not be asked: {}", e.getClass().getName());
      return Optional.empty();
    }
  }

  private static String field(String name, String value) {
    return name + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  @Override
  public String name() {
    return "turnstile";
  }
}
