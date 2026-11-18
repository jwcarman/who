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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.mock.web.MockHttpServletRequest;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class RequestLocalesTest {

  private final ExecutorService executor = Executors.newSingleThreadExecutor();

  @AfterEach
  void shutDown() {
    executor.shutdownNow();
    LocaleContextHolder.resetLocaleContext();
  }

  private Locale localeSeenOnAnotherThread(Runnable carried, AtomicReference<Locale> seen)
      throws Exception {
    Future<?> done = executor.submit(carried);
    done.get();
    return seen.get();
  }

  @Test
  void a_lazily_resolved_locale_is_resolved_before_the_request_ends() throws Exception {
    // DispatcherServlet binds one that asks the request each time; once the request is done, the
    // container recycles it and asking throws
    AtomicBoolean requestDone = new AtomicBoolean();
    LocaleContextHolder.setLocaleContext(
        () -> {
          if (requestDone.get()) {
            throw new IllegalStateException("The request object has been recycled");
          }
          return Locale.GERMAN;
        });
    AtomicReference<Locale> seen = new AtomicReference<>();

    Runnable carried =
        RequestLocales.carrying(
            new MockHttpServletRequest(), () -> seen.set(LocaleContextHolder.getLocale()));
    requestDone.set(true);

    assertThat(localeSeenOnAnotherThread(carried, seen)).isEqualTo(Locale.GERMAN);
  }

  @Test
  void the_locale_spring_bound_for_the_request_reaches_the_other_thread() throws Exception {
    LocaleContextHolder.setLocale(Locale.FRENCH);
    AtomicReference<Locale> seen = new AtomicReference<>();

    Runnable carried =
        RequestLocales.carrying(
            new MockHttpServletRequest(), () -> seen.set(LocaleContextHolder.getLocale()));

    assertThat(localeSeenOnAnotherThread(carried, seen)).isEqualTo(Locale.FRENCH);
  }

  @Test
  void without_a_bound_locale_the_requests_own_reaches_the_other_thread() throws Exception {
    LocaleContextHolder.resetLocaleContext();
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addPreferredLocale(Locale.GERMAN);
    AtomicReference<Locale> seen = new AtomicReference<>();

    Runnable carried =
        RequestLocales.carrying(request, () -> seen.set(LocaleContextHolder.getLocale()));

    assertThat(localeSeenOnAnotherThread(carried, seen)).isEqualTo(Locale.GERMAN);
  }

  @Test
  void the_other_threads_locale_is_restored_afterwards() throws Exception {
    Runnable italian = () -> LocaleContextHolder.setLocale(Locale.ITALIAN);
    executor.submit(italian).get();
    LocaleContextHolder.setLocale(Locale.FRENCH);

    executor.submit(RequestLocales.carrying(new MockHttpServletRequest(), () -> {})).get();

    Callable<Locale> current = LocaleContextHolder::getLocale;
    assertThat(executor.submit(current).get()).isEqualTo(Locale.ITALIAN);
  }
}
