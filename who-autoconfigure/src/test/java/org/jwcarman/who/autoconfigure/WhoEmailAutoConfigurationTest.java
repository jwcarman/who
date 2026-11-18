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
import static org.mockito.Mockito.mock;

import java.util.Locale;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.email.OutgoingEmail;
import org.jwcarman.who.core.email.WhoEmail;
import org.jwcarman.who.core.email.WhoEmailRenderer;
import org.jwcarman.who.core.email.WhoEmailSender;
import org.jwcarman.who.core.email.WhoEmails;
import org.jwcarman.who.email.MustacheWhoEmailRenderer;
import org.jwcarman.who.email.WhoEmailMessages;
import org.jwcarman.who.email.WhoEmailTemplates;
import org.jwcarman.who.email.smtp.SmtpWhoEmailSender;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.core.annotation.Order;
import org.springframework.mail.javamail.JavaMailSender;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class WhoEmailAutoConfigurationTest {

  record LibTest(String address) implements WhoEmail {
    @Override
    public String template() {
      return "lib-test";
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class OneLibrary {
    @Bean
    WhoEmailTemplates library() {
      return new WhoEmailTemplates("classpath:/lib-email/", "lib-email/lib-messages");
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class ValuedLibrary {
    @Bean
    WhoEmailTemplates library() {
      return new WhoEmailTemplates("classpath:/lib-email/", "lib-email/valued-messages");
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class TwoLibraries {
    @Bean
    @Order(2)
    WhoEmailTemplates second() {
      return new WhoEmailTemplates("classpath:/lib-email/", "lib-email/lib-messages");
    }

    @Bean
    @Order(1)
    WhoEmailTemplates first() {
      return new WhoEmailTemplates("classpath:/app-email/", null);
    }
  }

  private static OutgoingEmail renderLibTest(AssertableApplicationContext context) {
    return context
        .getBean(WhoEmailRenderer.class)
        .render(new LibTest("ann@example.com"), Locale.ENGLISH);
  }

  record Hello(String address) implements WhoEmail {
    @Override
    public String template() {
      return "hello";
    }
  }

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(WhoEmailAutoConfiguration.class));

  private final JavaMailSender mailSender = mock(JavaMailSender.class);

  @Nested
  class Beans {

    @Test
    void renderer_sender_and_who_emails_with_mail_and_from() {
      runner
          .withBean(JavaMailSender.class, () -> mailSender)
          .withPropertyValues("who.email.from=no-reply@who.dev")
          .run(
              context -> {
                assertThat(context).hasSingleBean(MustacheWhoEmailRenderer.class);
                assertThat(context).hasSingleBean(SmtpWhoEmailSender.class);
                assertThat(context).hasSingleBean(WhoEmails.class);
              });
    }

    @Test
    void no_who_emails_without_a_sender() {
      runner.run(
          context -> {
            assertThat(context).hasSingleBean(WhoEmailRenderer.class);
            assertThat(context).doesNotHaveBean(WhoEmailSender.class);
            assertThat(context).doesNotHaveBean(WhoEmails.class);
          });
    }

    @Test
    void a_custom_sender_needs_no_mail_host() {
      runner
          .withBean(WhoEmailSender.class, () -> email -> {})
          .run(
              context -> {
                assertThat(context).hasSingleBean(WhoEmails.class);
                assertThat(context).doesNotHaveBean(SmtpWhoEmailSender.class);
              });
    }

    @Test
    void a_custom_renderer_replaces_mustache() {
      WhoEmailRenderer custom =
          (email, locale) -> new OutgoingEmail(email.address(), "s", "t", null, email);
      runner
          .withBean(WhoEmailRenderer.class, () -> custom)
          .withBean(WhoEmailSender.class, () -> email -> {})
          .run(
              context -> {
                assertThat(context).doesNotHaveBean(MustacheWhoEmailRenderer.class);
                assertThat(context.getBean(WhoEmailRenderer.class)).isSameAs(custom);
                assertThat(context).hasSingleBean(WhoEmails.class);
              });
    }

    @Test
    void smtp_without_a_from_address_fails_startup() {
      runner
          .withBean(JavaMailSender.class, () -> mailSender)
          .run(
              context ->
                  assertThat(context)
                      .getFailure()
                      .rootCause()
                      .hasMessageContaining("who.email.from"));
    }
  }

  @Nested
  class Libraries {

    @Test
    void a_librarys_template_and_subject_are_used() {
      runner
          .withUserConfiguration(OneLibrary.class)
          .withBean(WhoEmailSender.class, () -> email -> {})
          .run(
              context -> {
                OutgoingEmail out = renderLibTest(context);
                assertThat(out.text()).isEqualTo("From the library");
                assertThat(out.subject()).isEqualTo("Library subject");
              });
    }

    @Test
    void a_librarys_subject_beats_whos_own_for_the_same_key() {
      runner
          .withUserConfiguration(OneLibrary.class)
          .withBean(WhoEmailSender.class, () -> email -> {})
          .withBean(
              WhoEmailMessages.class, () -> new WhoEmailMessages("who-email-test/who-messages"))
          .run(
              context -> assertThat(renderLibTest(context).subject()).isEqualTo("Library subject"));
    }

    @Test
    void whos_own_subject_answers_without_a_library() {
      runner
          .withBean(WhoEmailSender.class, () -> email -> {})
          .withBean(
              WhoEmailMessages.class, () -> new WhoEmailMessages("who-email-test/who-messages"))
          .withPropertyValues("who.email.templates=classpath:/lib-email/")
          .run(context -> assertThat(renderLibTest(context).subject()).isEqualTo("Who subject"));
    }

    @Test
    void a_librarys_subject_names_the_app_from_properties() {
      runner
          .withUserConfiguration(ValuedLibrary.class)
          .withBean(WhoEmailSender.class, () -> email -> {})
          .withPropertyValues("who.email.model.name=Roux")
          .run(
              context -> assertThat(renderLibTest(context).subject()).isEqualTo("Sign in to Roux"));
    }

    @Test
    void the_applications_location_beats_a_librarys() {
      runner
          .withUserConfiguration(OneLibrary.class)
          .withBean(WhoEmailSender.class, () -> email -> {})
          .withPropertyValues("who.email.templates=classpath:/app-email/")
          .run(context -> assertThat(renderLibTest(context).text()).isEqualTo("From the app"));
    }

    @Test
    void the_applications_subject_beats_a_librarys() {
      StaticMessageSource app = new StaticMessageSource();
      app.addMessage("who.email.lib-test.subject", Locale.ENGLISH, "App subject");
      runner
          .withUserConfiguration(OneLibrary.class)
          .withBean(WhoEmailSender.class, () -> email -> {})
          .withBean("messageSource", MessageSource.class, () -> app)
          .run(context -> assertThat(renderLibTest(context).subject()).isEqualTo("App subject"));
    }

    @Test
    void libraries_are_searched_in_bean_order() {
      runner
          .withUserConfiguration(TwoLibraries.class)
          .withBean(WhoEmailSender.class, () -> email -> {})
          .run(context -> assertThat(renderLibTest(context).text()).isEqualTo("From the app"));
    }
  }

  @Nested
  class Locales {

    @Test
    void the_locale_falls_back_to_who_email_locale() {
      Locale[] seen = new Locale[1];
      WhoEmailRenderer recording =
          (email, locale) -> {
            seen[0] = locale;
            return new OutgoingEmail(email.address(), "s", "t", null, email);
          };
      runner
          .withBean(WhoEmailRenderer.class, () -> recording)
          .withBean(WhoEmailSender.class, () -> email -> {})
          .withPropertyValues("who.email.locale=fr_CA")
          .run(
              context -> {
                context.getBean(WhoEmails.class).send(new Hello("ann@example.com"));
                assertThat(seen[0]).isEqualTo(Locale.CANADA_FRENCH);
              });
    }
  }
}
