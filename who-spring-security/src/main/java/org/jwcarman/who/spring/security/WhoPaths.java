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
package org.jwcarman.who.spring.security;

import static java.util.Objects.requireNonNull;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Where every who endpoint lives: one root, such as {@code /who} (the default) or {@code /auth},
 * and each endpoint's path below it. Sign-in methods, who's own filters and {@code /me} all take
 * their paths from here, so they cannot disagree.
 *
 * @param root a path such as {@code /who}: starting with {@code /}, not ending with one, and with
 *     no wildcard, template, query, fragment, parameter or whitespace characters
 */
public record WhoPaths(String root) {

  /** The root when none is configured. */
  public static final String DEFAULT_ROOT = "/who";

  /**
   * The root as a placeholder for a request mapping, {@code who.base-path} or {@link
   * #DEFAULT_ROOT}: {@code @GetMapping(WhoPaths.MAPPING + "/me")}. The {@code WhoPaths} bean checks
   * the same property at startup.
   */
  public static final String MAPPING = "${who.base-path:" + DEFAULT_ROOT + "}";

  private static final String FORBIDDEN = "*{}?#;";

  /**
   * Creates the paths under {@code root}.
   *
   * @throws IllegalArgumentException when {@code root} is not such a path
   */
  public WhoPaths {
    requireNonNull(root, "root must not be null");
    String problem = problem(root);
    if (problem != null) {
      throw new IllegalArgumentException("\"" + root + "\" " + problem);
    }
  }

  /**
   * The paths under {@link #DEFAULT_ROOT}.
   *
   * @return the default paths
   */
  public static WhoPaths defaults() {
    return new WhoPaths(DEFAULT_ROOT);
  }

  /**
   * Returns an endpoint's full path.
   *
   * @param relative the endpoint's path below the root, starting with {@code /}, such as {@code
   *     /me}
   * @return the full path, such as {@code /who/me}
   * @throws IllegalArgumentException when {@code relative} does not start with {@code /}
   */
  public String of(String relative) {
    if (!relative.startsWith("/")) {
      throw new IllegalArgumentException("\"" + relative + "\" must start with /");
    }
    return root + relative;
  }

  /**
   * Whether {@code request} is for one of who's endpoints: its path, without the context path, is
   * the root or below it.
   *
   * @param request the request
   * @return whether who's base path holds it
   */
  public boolean contains(HttpServletRequest request) {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    return path.equals(root) || path.startsWith(root + "/");
  }

  private static String problem(String root) {
    if (!root.startsWith("/")) {
      return "must start with /";
    }
    if (root.length() == 1) {
      return "must name a path below /, such as /who";
    }
    if (root.endsWith("/")) {
      return "must not end with /";
    }
    for (int i = 0; i < root.length(); i++) {
      char c = root.charAt(i);
      if (FORBIDDEN.indexOf(c) >= 0 || Character.isWhitespace(c)) {
        return "must not contain '" + c + "'";
      }
    }
    return null;
  }

  /** Returns the root, as the paths print in logs and messages. */
  @Override
  public String toString() {
    return root;
  }
}
