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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.context.support.StaticMessageSource;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class AppFirstMessageSourceTest {

  private static final String CODE = "who.email.sign-in.subject";

  private final StaticMessageSource who = new StaticMessageSource();
  private final StaticMessageSource app = new StaticMessageSource();

  AppFirstMessageSourceTest() {
    who.addMessage(CODE, Locale.ENGLISH, "Your sign-in link and code");
  }

  private AppFirstMessageSource messages() {
    return new AppFirstMessageSource(app, who);
  }

  @Test
  void the_applications_message_wins() {
    app.addMessage(CODE, Locale.ENGLISH, "Sign in to Acme");

    assertThat(messages().getMessage(CODE, null, Locale.ENGLISH)).isEqualTo("Sign in to Acme");
  }

  @Test
  void a_message_the_application_lacks_comes_from_who() {
    assertThat(messages().getMessage(CODE, null, Locale.ENGLISH))
        .isEqualTo("Your sign-in link and code");
  }

  @Test
  void an_application_that_answers_with_the_code_still_gets_whos_message() {
    // spring.messages.use-code-as-default-message=true: the app's source returns the code itself
    app.setUseCodeAsDefaultMessage(true);

    assertThat(messages().getMessage(CODE, null, Locale.ENGLISH))
        .isEqualTo("Your sign-in link and code");
    assertThat(messages().getMessage(CODE, null, null, Locale.ENGLISH))
        .isEqualTo("Your sign-in link and code");
    assertThat(messages().getMessage(new DefaultMessageSourceResolvable(CODE), Locale.ENGLISH))
        .isEqualTo("Your sign-in link and code");
  }
}
