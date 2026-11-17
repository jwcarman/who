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

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The paths a signed-out request may reach: Spring Boot's error page, {@code GET /who/sign-in},
 * every sign-in method's own endpoints, the application's {@code who.security.public-paths} and
 * what {@link WhoPublicPathContributor} beans add. who's default security chain permits them; an
 * application that writes its own chain can do the same with {@code
 * requestMatchers(whoPublicPaths.patterns()).permitAll()}.
 */
public final class WhoPublicPaths {

  /** Spring Boot's error page, so an error answer is never hidden behind a {@code 401}. */
  public static final String ERROR_PAGE = "/error";

  /** Where a sign-in page learns which methods are on, below the root. */
  static final String SIGN_IN_OPTIONS = "/sign-in";

  private final Set<String> patterns = new LinkedHashSet<>();

  /**
   * Collects the paths, with none contributed by libraries.
   *
   * @param paths where who's endpoints live, for {@code GET /who/sign-in}
   * @param methods the browser sign-in methods, whose {@link PasswordlessLoginMethod#publicPaths()}
   *     are added
   * @param configured the application's own public paths
   */
  public WhoPublicPaths(
      WhoPaths paths,
      Collection<? extends PasswordlessLoginMethod> methods,
      Collection<String> configured) {
    this(paths, methods, configured, List.of());
  }

  /**
   * Collects the paths.
   *
   * @param paths where who's endpoints live, for {@code GET /who/sign-in}
   * @param methods the browser sign-in methods, whose {@link PasswordlessLoginMethod#publicPaths()}
   *     are added
   * @param configured the application's own public paths
   * @param contributed the paths {@link WhoPublicPathContributor} beans added, app-root patterns
   *     like the configured ones
   */
  public WhoPublicPaths(
      WhoPaths paths,
      Collection<? extends PasswordlessLoginMethod> methods,
      Collection<String> configured,
      Collection<String> contributed) {
    patterns.add(ERROR_PAGE);
    patterns.add(paths.of(SIGN_IN_OPTIONS));
    methods.forEach(method -> patterns.addAll(method.publicPaths()));
    patterns.addAll(configured);
    patterns.addAll(contributed);
  }

  /**
   * Returns the path patterns, the error page first, then the sign-in options, then each method's,
   * then the application's, then those contributed; a repeated pattern keeps its first place.
   *
   * @return the patterns, for {@code requestMatchers(...)}
   */
  public String[] patterns() {
    return patterns.toArray(String[]::new);
  }
}
