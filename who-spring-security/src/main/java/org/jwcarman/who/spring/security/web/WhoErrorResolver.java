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

import java.io.IOException;
import java.io.UncheckedIOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jspecify.annotations.Nullable;
import org.jwcarman.who.spring.security.WhoPaths;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/**
 * who's place in Spring MVC's exception handling; put it first among the resolvers (who's
 * autoconfiguration does, through {@code extendHandlerExceptionResolvers}). Under who's base path
 * it answers every exception itself with its {@link WhoProblem}, so no application advice changes
 * who's error shape. Elsewhere it only names a who exception in the {@code Who-Error} header and
 * leaves the answer to the application's advice, or Spring's {@code @ResponseStatus} handling. It
 * is the controller side of what {@link WhoExceptionHandoff} does for filters.
 */
public final class WhoErrorResolver implements HandlerExceptionResolver {

  private final WhoPaths paths;

  /**
   * Creates the resolver.
   *
   * @param paths who's base path, under which who answers errors itself
   */
  public WhoErrorResolver(WhoPaths paths) {
    this.paths = requireNonNull(paths, "paths must not be null");
  }

  @Override
  public @Nullable ModelAndView resolveException(
      HttpServletRequest request,
      HttpServletResponse response,
      @Nullable Object handler,
      Exception exception) {
    if (response.isCommitted()) {
      return null;
    }
    if (paths.contains(request)) {
      try {
        WhoProblems.write(request, response, exception, HttpStatus.INTERNAL_SERVER_ERROR);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      // empty: answered, nothing to render
      return new ModelAndView();
    }
    String code = WhoProblems.codeOf(exception);
    if (code != null) {
      response.setHeader(WhoExceptionHandoff.HEADER, code);
    }
    return null;
  }
}
