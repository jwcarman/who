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
package org.jwcarman.who.emaillink;

import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.i18n.SimpleLocaleContext;

/**
 * Carries a request's locale to work done off the request thread, so an email issued there is
 * rendered in the language of the person who asked for it.
 */
final class RequestLocales {

  private RequestLocales() {}

  /**
   * The task, run with the request's locale bound: the one Spring bound for the request, else the
   * request's own ({@code Accept-Language}). Whatever was bound before is restored afterwards.
   */
  static Runnable carrying(HttpServletRequest request, Runnable task) {
    LocaleContext locale =
        Optional.ofNullable(LocaleContextHolder.getLocaleContext())
            .orElseGet(() -> new SimpleLocaleContext(request.getLocale()));
    return () -> {
      LocaleContext previous = LocaleContextHolder.getLocaleContext();
      LocaleContextHolder.setLocaleContext(locale);
      try {
        task.run();
      } finally {
        LocaleContextHolder.setLocaleContext(previous);
      }
    };
  }
}
