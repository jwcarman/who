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
package org.jwcarman.who.autoconfigure;

import static java.util.Objects.requireNonNull;

import java.util.Arrays;
import java.util.Locale;

import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.NoSuchMessageException;

/**
 * The application's messages first, then the email bundles: a message the application defines wins,
 * and one it lacks comes from the libraries' bundles ({@code WhoEmailTemplates}), then who's. An
 * application message that is only its own code counts as lacking, since {@code
 * spring.messages.use-code-as-default-message} answers every code that way.
 */
final class AppFirstMessageSource implements MessageSource {

  private final MessageSource app;
  private final MessageSource who;

  AppFirstMessageSource(MessageSource app, MessageSource who) {
    this.app = requireNonNull(app, "app must not be null");
    this.who = requireNonNull(who, "who must not be null");
  }

  @Override
  public @Nullable String getMessage(
      String code, Object @Nullable [] args, @Nullable String defaultMessage, Locale locale) {
    String message = app.getMessage(code, args, null, locale);
    return message != null && !message.equals(code)
        ? message
        : who.getMessage(code, args, defaultMessage, locale);
  }

  @Override
  public String getMessage(String code, Object @Nullable [] args, Locale locale) {
    try {
      String message = app.getMessage(code, args, locale);
      if (!message.equals(code)) {
        return message;
      }
    } catch (NoSuchMessageException _) {
      // the application lacks it: who's answers below
    }
    return who.getMessage(code, args, locale);
  }

  @Override
  public String getMessage(MessageSourceResolvable resolvable, Locale locale) {
    try {
      String message = app.getMessage(resolvable, locale);
      String[] codes = resolvable.getCodes();
      if (codes == null || !Arrays.asList(codes).contains(message)) {
        return message;
      }
    } catch (NoSuchMessageException _) {
      // the application lacks it: who's answers below
    }
    return who.getMessage(resolvable, locale);
  }
}
