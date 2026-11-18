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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.email.OutgoingEmail;
import org.jwcarman.who.core.email.WhoEmail;
import org.jwcarman.who.email.MustacheWhoEmailRenderer;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** who's built-in email-link templates, rendered against real records, word for word. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class BuiltInTemplatesTest {

  private static final String LINK = "https://app.test/sign-in#token=abc&x=1";
  private static final Instant EXPIRES = Instant.parse("2026-11-17T12:15:00Z");
  private static final Duration FIFTEEN = Duration.ofMinutes(15);

  private final MustacheWhoEmailRenderer renderer =
      new MustacheWhoEmailRenderer(
          new PathMatchingResourcePatternResolver(),
          List.of("classpath:/no-app-templates/"),
          messages(),
          Map.of(),
          List.of());

  private static ResourceBundleMessageSource messages() {
    ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
    messages.setBasenames("org/jwcarman/who/emaillink/email-messages");
    messages.setDefaultEncoding("UTF-8");
    messages.setFallbackToSystemLocale(false);
    return messages;
  }

  private OutgoingEmail render(WhoEmail email) {
    return renderer.render(email, Locale.ENGLISH);
  }

  @Nested
  class SignIn {

    @Test
    void renders_todays_subject_and_text() {
      OutgoingEmail out =
          render(new EmailLinkMessage("ann@example.com", LINK, "K7F2QM", EXPIRES, FIFTEEN));

      assertThat(out.subject()).isEqualTo("Your sign-in link and code");
      assertThat(out.text())
          .isEqualTo(
              "Sign in: "
                  + LINK
                  + "\n\nOr enter this code: K7F-2QM"
                  + "\n\nThe link and the code expire in 15 minutes and work once."
                  + "\n\nIf you didn't ask to sign in, ignore this email."
                  + " Never share this code with anyone.");
      assertThat(out.html()).isNull();
    }

    @Test
    void one_minute_is_singular() {
      OutgoingEmail out =
          render(
              new EmailLinkMessage(
                  "ann@example.com", LINK, "K7F2QM", EXPIRES, Duration.ofSeconds(30)));

      assertThat(out.text()).contains("expire in 1 minute and work once.");
    }
  }

  @Nested
  class Invitation {

    @Test
    void renders_todays_subject_and_text() {
      OutgoingEmail out =
          render(
              new InvitationMessage(
                  "new@example.com", LINK, "K7F2QM", EXPIRES, FIFTEEN, Set.of("admin")));

      assertThat(out.subject()).isEqualTo("You have been invited");
      assertThat(out.text())
          .isEqualTo(
              "You have been invited. Accept by signing in: "
                  + LINK
                  + "\n\nOr enter this code: K7F-2QM"
                  + "\n\nThe link and the code expire in 15 minutes and work once."
                  + "\n\nIf you weren't expecting this invitation, ignore this email."
                  + " Never share this code with anyone.");
    }
  }

  @Nested
  class AddressConfirmation {

    private static final String TAIL =
        """


        Or enter this code: K7F-2QM

        The link and the code expire in 15 minutes and work once, while you are \
        signed in.

        If you didn't ask to add this address, ignore this email and nothing changes. \
        Never share this code with anyone.""";

    @Test
    void an_added_address_renders_todays_subject_and_text() {
      OutgoingEmail out =
          render(
              new EmailAddressConfirmationMessage(
                  "add@example.com", LINK, "K7F2QM", EXPIRES, FIFTEEN, false));

      assertThat(out.subject()).isEqualTo("Confirm your email address");
      assertThat(out.text()).isEqualTo("Add this address to your account: " + LINK + TAIL);
    }

    @Test
    void a_changed_address_renders_todays_text() {
      OutgoingEmail out =
          render(
              new EmailAddressConfirmationMessage(
                  "new@example.com", LINK, "K7F2QM", EXPIRES, FIFTEEN, true));

      assertThat(out.text())
          .isEqualTo(
              "Make this your account's email address, in place of the old one: " + LINK + TAIL);
    }
  }

  @Nested
  class AddressChanged {

    @Test
    void renders_todays_subject_and_text() {
      OutgoingEmail out =
          render(new EmailAddressChangedMessage("old@example.com", "newname@example.com"));

      assertThat(out.subject()).isEqualTo("Your email address was changed");
      assertThat(out.text())
          .isEqualTo(
              """
              Your account's email address was changed to n***@example.com. \
              This address no longer signs in.

              If you didn't make this change, someone else may have access to your \
              account: contact the site right away.""");
    }
  }
}
