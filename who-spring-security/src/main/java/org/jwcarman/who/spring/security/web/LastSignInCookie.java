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
package org.jwcarman.who.spring.security.web;

import static java.util.Objects.requireNonNull;

import java.time.Duration;

import jakarta.servlet.http.HttpServletResponse;

import org.jwcarman.who.core.event.SignInMethod;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

/**
 * Remembers in the browser which way someone last signed in, so the sign-in page can mark that
 * button "Last used" and a person who forgot does not create a second account another way.
 *
 * <p>The cookie {@value #NAME} holds only the method ({@code email-link}, {@code passkey}, or a
 * provider's registration id such as {@code github}): scripts can read it, it is {@code Secure} and
 * {@code SameSite=Lax}, it lasts 30 days, and signing out leaves it. Turn it off with {@code
 * who.security.last-sign-in-cookie.enabled=false}.
 */
public final class LastSignInCookie {

  /** The cookie's name. */
  public static final String NAME = "who_last_sign_in";

  private static final Duration MAX_AGE = Duration.ofDays(30);

  /** Creates the cookie writer. */
  public LastSignInCookie() {}

  /**
   * Sets the cookie on the response.
   *
   * @param response the sign-in's response
   * @param value the method, as {@link #valueFor(SignInMethod)} names it, or a registration id
   */
  public void write(HttpServletResponse response, String value) {
    requireNonNull(value, "value must not be null");
    ResponseCookie cookie =
        ResponseCookie.from(NAME, value)
            .path("/")
            .maxAge(MAX_AGE)
            .secure(true)
            .httpOnly(false)
            .sameSite("Lax")
            .build();
    response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
  }

  /**
   * Names a browser sign-in method as the cookie records it.
   *
   * @param method the method
   * @return {@code email-link} for a link or a code, {@code passkey} for a passkey
   * @throws IllegalArgumentException for a method with no browser sign-in of its own
   */
  public static String valueFor(SignInMethod method) {
    return switch (requireNonNull(method, "method must not be null")) {
      case EMAIL_LINK, EMAIL_CODE -> "email-link";
      case PASSKEY -> "passkey";
      case API_KEY, JWT, OIDC ->
          throw new IllegalArgumentException(
              method + " is recorded by registration id, or not at all");
    };
  }
}
