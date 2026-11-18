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

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.i18n.SimpleLocaleContext;
import org.springframework.context.i18n.SimpleTimeZoneAwareLocaleContext;
import org.springframework.context.i18n.TimeZoneAwareLocaleContext;

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
    LocaleContext locale = resolvedNow(request);
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

  /**
   * The request's locale, resolved on the request thread. Spring MVC binds a context that asks the
   * request each time, and once the request ends the container recycles it, so asking from another
   * thread afterwards fails.
   */
  private static LocaleContext resolvedNow(HttpServletRequest request) {
    LocaleContext bound = LocaleContextHolder.getLocaleContext();
    if (bound == null) {
      return new SimpleLocaleContext(request.getLocale());
    }
    if (bound instanceof TimeZoneAwareLocaleContext zoned) {
      return new SimpleTimeZoneAwareLocaleContext(zoned.getLocale(), zoned.getTimeZone());
    }
    return new SimpleLocaleContext(bound.getLocale());
  }
}
