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

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

import org.jwcarman.who.core.email.WhoEmailRenderer;
import org.jwcarman.who.core.email.WhoEmailSender;
import org.jwcarman.who.core.email.WhoEmails;
import org.jwcarman.who.email.MustacheWhoEmailRenderer;
import org.jwcarman.who.email.WhoEmailMessages;
import org.jwcarman.who.email.WhoEmailModelContributor;
import org.jwcarman.who.email.smtp.SmtpWhoEmailSender;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.util.StringUtils;

/**
 * Wires who's one email seam: a {@link WhoEmailRenderer} ({@code who-email}'s Mustache renderer
 * unless the application has its own), a {@link WhoEmailSender} ({@code who-email-smtp}'s unless
 * the application has its own), and {@link WhoEmails} when both exist. Features send only through
 * {@link WhoEmails}; one that must send email fails startup without it.
 */
@AutoConfiguration(
    afterName = "org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration")
@EnableConfigurationProperties(WhoProperties.class)
public class WhoEmailAutoConfiguration {

  /** Creates the autoconfiguration; Spring Boot calls it. */
  public WhoEmailAutoConfiguration() {
    // nothing to set up: every bean is built by a method below
  }

  /**
   * The seam, when there is a renderer and a sender. Emails render in the request's locale when one
   * is bound, else in {@code who.email.locale}, else the JVM's.
   *
   * @param renderer renders each email
   * @param sender delivers each email
   * @param properties who's properties
   * @return the seam
   */
  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean({WhoEmailRenderer.class, WhoEmailSender.class})
  public WhoEmails whoEmails(
      WhoEmailRenderer renderer, WhoEmailSender sender, WhoProperties properties) {
    return new WhoEmails(renderer, sender, locale(properties));
  }

  private static Supplier<Locale> locale(WhoProperties properties) {
    Locale fallback =
        Optional.ofNullable(properties.getEmail().getLocale()).orElseGet(Locale::getDefault);
    return () ->
        LocaleContextHolder.getLocaleContext() != null ? LocaleContextHolder.getLocale() : fallback;
  }

  /** who-email's Mustache renderer, unless the application has its own. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "org.jwcarman.who.email.MustacheWhoEmailRenderer")
  static class MustacheRendererConfiguration {

    @Bean
    @ConditionalOnMissingBean(WhoEmailRenderer.class)
    public MustacheWhoEmailRenderer mustacheWhoEmailRenderer(
        ResourcePatternResolver resources,
        ObjectProvider<MessageSource> messageSource,
        ObjectProvider<WhoEmailMessages> bundles,
        ObjectProvider<WhoEmailModelContributor> contributors,
        WhoProperties properties) {
      ResourceBundleMessageSource who = new ResourceBundleMessageSource();
      who.setDefaultEncoding("UTF-8");
      who.setFallbackToSystemLocale(false);
      bundles.orderedStream().map(WhoEmailMessages::basename).forEach(who::addBasenames);
      MessageSource app = messageSource.getIfAvailable(StaticMessageSource::new);
      WhoProperties.Email email = properties.getEmail();
      return new MustacheWhoEmailRenderer(
          resources,
          List.of(email.getTemplates()),
          new AppFirstMessageSource(app, who),
          email.getModel(),
          List.copyOf(contributors.orderedStream().toList()));
    }
  }

  /** who-email-smtp's sender, when Spring has a mail sender and the application has no sender. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(
      name = {
        "org.jwcarman.who.email.smtp.SmtpWhoEmailSender",
        "org.springframework.mail.javamail.JavaMailSender"
      })
  static class SmtpSenderConfiguration {

    @Bean
    @ConditionalOnMissingBean(WhoEmailSender.class)
    @ConditionalOnBean(JavaMailSender.class)
    public SmtpWhoEmailSender smtpWhoEmailSender(
        JavaMailSender mailSender, WhoProperties properties) {
      String from = properties.getEmail().getFrom();
      if (!StringUtils.hasText(from)) {
        throw new WhoConfigurationException(
            "who.email.from",
            "must be set to the address who's emails come from",
            "Set it, such as no-reply@example.com, or remove who-email-smtp.");
      }
      return new SmtpWhoEmailSender(mailSender, from);
    }
  }
}
