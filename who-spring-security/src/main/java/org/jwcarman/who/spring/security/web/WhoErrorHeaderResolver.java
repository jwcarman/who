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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jspecify.annotations.Nullable;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/**
 * Names a who exception thrown by a controller in the {@code Who-Error} header, as {@link
 * WhoExceptionHandoff} does for one raised in a filter. Put it first among Spring MVC's resolvers
 * (who's autoconfiguration does, through {@code extendHandlerExceptionResolvers}); it answers
 * nothing itself, so the application's advice (or Spring's {@code @ResponseStatus} handling) still
 * writes the answer, with the header beside it.
 */
public final class WhoErrorHeaderResolver implements HandlerExceptionResolver {

  @Override
  public @Nullable ModelAndView resolveException(
      HttpServletRequest request,
      HttpServletResponse response,
      @Nullable Object handler,
      Exception exception) {
    String code = WhoExceptionHandoff.codeOf(exception);
    if (code != null && !response.isCommitted()) {
      response.setHeader(WhoExceptionHandoff.HEADER, code);
    }
    return null;
  }
}
