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
package org.jwcarman.who.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class OidcRedirectsTest {

  @Test
  void failure_url_keeps_its_query_and_fragment() {
    OidcRedirects redirects = new OidcRedirects("/", "/signin?x=1#top");

    assertThat(redirects.failure("signed-in")).isEqualTo("/signin?x=1&error=signed-in#top");
  }

  @Test
  void failure_url_without_a_query_gets_one() {
    assertThat(new OidcRedirects("/", "/signin").failure("signin-failed"))
        .isEqualTo("/signin?error=signin-failed");
  }

  @Test
  void success_url_is_returned_as_configured() {
    assertThat(new OidcRedirects("/home?tab=1", "/").success()).isEqualTo("/home?tab=1");
  }

  @Test
  void rejects_absolute_and_protocol_relative_urls() {
    for (String url :
        new String[] {"https://evil.example.com/", "//evil.example.com/", "home", ""}) {
      assertThatThrownBy(() -> new OidcRedirects(url, "/"))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new OidcRedirects("/", url))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new OidcRedirects("/", "/", url))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void a_pending_sign_up_lands_on_the_failure_url() {
    assertThat(new OidcRedirects("/home", "/signin?x=1#top").pendingSignUp())
        .isEqualTo("/signin?x=1&pending=sign-up#top");
  }

  @Test
  void a_link_lands_on_the_link_url() {
    OidcRedirects redirects = new OidcRedirects("/home", "/signin", "/settings?tab=accounts");

    assertThat(redirects.pendingLink()).isEqualTo("/settings?tab=accounts&pending=link");
    assertThat(redirects.link()).isEqualTo("/settings?tab=accounts");
    assertThat(redirects.linkFailure("already-linked"))
        .isEqualTo("/settings?tab=accounts&error=already-linked");
  }

  @Test
  void the_link_url_defaults_to_the_success_url() {
    OidcRedirects redirects = new OidcRedirects("/home", "/signin");

    assertThat(redirects.pendingLink()).isEqualTo("/home?pending=link");
    assertThat(redirects.link()).isEqualTo("/home");
  }

  @Test
  void a_local_path_is_local_and_one_that_is_not_a_uri_is_not() {
    assertThat(OidcRedirects.isLocalPath("/home?tab=keys#top")).isTrue();
    assertThat(OidcRedirects.isLocalPath("/a path with spaces")).isFalse();
    assertThat(OidcRedirects.isLocalPath(null)).isFalse();
  }

  @Test
  void rejects_a_backslash_that_browsers_read_as_protocol_relative() {
    assertThatThrownBy(() -> new OidcRedirects("/\\evil.example.com", "/"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
