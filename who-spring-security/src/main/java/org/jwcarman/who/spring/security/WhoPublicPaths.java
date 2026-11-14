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
import java.util.Set;

/**
 * The paths a signed-out request may reach: Spring Boot's error page, every sign-in method's own
 * endpoints, and the application's {@code who.security.public-paths}. who's default security chain
 * permits them; an application that writes its own chain can do the same with {@code
 * requestMatchers(whoPublicPaths.patterns()).permitAll()}.
 */
public final class WhoPublicPaths {

  /** Spring Boot's error page, so an error answer is never hidden behind a {@code 401}. */
  public static final String ERROR_PAGE = "/error";

  private final Set<String> patterns = new LinkedHashSet<>();

  /**
   * Collects the paths.
   *
   * @param methods the browser sign-in methods, whose {@link PasswordlessLoginMethod#publicPaths()}
   *     are added
   * @param configured the application's own public paths
   */
  public WhoPublicPaths(
      Collection<? extends PasswordlessLoginMethod> methods, Collection<String> configured) {
    patterns.add(ERROR_PAGE);
    methods.forEach(method -> patterns.addAll(method.publicPaths()));
    patterns.addAll(configured);
  }

  /**
   * Returns the path patterns, the error page first, then each method's, then the application's.
   *
   * @return the patterns, for {@code requestMatchers(...)}
   */
  public String[] patterns() {
    return patterns.toArray(String[]::new);
  }
}
