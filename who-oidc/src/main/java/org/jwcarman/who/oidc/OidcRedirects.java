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

import java.net.URI;

import org.springframework.web.util.UriComponentsBuilder;

/**
 * Where a provider sign-in sends the browser: the success URL, or the failure URL with an {@code
 * error} code added to its query.
 */
public final class OidcRedirects {

  /** The provider sign-in failed; the event says why. */
  public static final String SIGNIN_FAILED = "signin-failed";

  /** No identity has this provider account; a pending sign-up is waiting. */
  public static final String NO_ACCOUNT = "no-account";

  /** The provider account belongs to another identity than the one signed in. */
  public static final String ALREADY_LINKED = "already-linked";

  private final String successUrl;
  private final String failureUrl;

  /**
   * Creates the redirects.
   *
   * @param successUrl where a sign-in lands; a local path
   * @param failureUrl where every other outcome lands; a local path
   * @throws IllegalArgumentException when either is not a local path
   */
  public OidcRedirects(String successUrl, String failureUrl) {
    this.successUrl = localPath(successUrl, "success-url");
    this.failureUrl = localPath(failureUrl, "failure-url");
  }

  /**
   * The success URL.
   *
   * @return the URL as configured
   */
  public String success() {
    return successUrl;
  }

  String failureUrl() {
    return failureUrl;
  }

  /**
   * The failure URL with {@code error=<code>} added to its query, keeping its fragment.
   *
   * @param errorCode the code, such as {@link #NO_ACCOUNT}
   * @return the URL
   */
  public String failure(String errorCode) {
    return UriComponentsBuilder.fromUriString(failureUrl)
        .queryParam("error", errorCode)
        .build()
        .toUriString();
  }

  /**
   * Whether a URL is a path on this site: it starts with {@code /}, but not with {@code //} or a
   * slash and a backslash, which browsers read as another host.
   *
   * @param url the URL
   * @return {@code true} for a local path
   */
  public static boolean isLocalPath(String url) {
    if (url == null || !url.startsWith("/") || url.startsWith("//") || url.startsWith("/\\")) {
      return false;
    }
    try {
      return !URI.create(url.replace("\\", "%5C")).isAbsolute();
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private static String localPath(String url, String name) {
    if (!isLocalPath(url)) {
      throw new IllegalArgumentException(
          "who.oidc." + name + " must be a local path such as /signin, not " + url);
    }
    return url;
  }
}
