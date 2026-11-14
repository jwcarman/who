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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.event.SignInMethod;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class LastSignInCookieTest {

  @Nested
  class Writing {

    @Test
    void writes_a_script_readable_lax_secure_cookie_for_30_days() {
      MockHttpServletResponse response = new MockHttpServletResponse();

      new LastSignInCookie().write(response, "passkey");

      String header = response.getHeader(HttpHeaders.SET_COOKIE);
      assertThat(header)
          .startsWith("who_last_sign_in=passkey")
          .contains("Path=/", "Max-Age=2592000", "Secure", "SameSite=Lax")
          .doesNotContain("HttpOnly");
    }

    @Test
    void writes_a_registration_id() {
      MockHttpServletResponse response = new MockHttpServletResponse();

      new LastSignInCookie().write(response, "github");

      assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).startsWith("who_last_sign_in=github;");
    }
  }

  @Nested
  class Values {

    @Test
    void email_link_and_email_code_report_email_link() {
      assertThat(LastSignInCookie.valueFor(SignInMethod.EMAIL_LINK)).isEqualTo("email-link");
      assertThat(LastSignInCookie.valueFor(SignInMethod.EMAIL_CODE)).isEqualTo("email-link");
    }

    @Test
    void passkey_reports_passkey() {
      assertThat(LastSignInCookie.valueFor(SignInMethod.PASSKEY)).isEqualTo("passkey");
    }

    @Test
    void a_method_with_no_browser_sign_in_has_no_value() {
      assertThatThrownBy(() -> LastSignInCookie.valueFor(SignInMethod.API_KEY))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
