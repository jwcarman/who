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

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.sql.init.DatabaseInitializationMode;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class WhoPropertiesTest {

  @Test
  void every_module_switch_and_the_remaining_settings_bind_by_their_documented_names() {
    WhoProperties props =
        new Binder(
                new MapConfigurationPropertySource(
                    Map.ofEntries(
                        Map.entry("who.passkey.enabled", "false"),
                        Map.entry("who.email-link.enabled", "false"),
                        Map.entry("who.email-link.address-confirmation-subject", "Confirm it"),
                        Map.entry("who.email-link.address-changed-subject", "It changed"),
                        Map.entry("who.enrollment.enabled", "false"),
                        Map.entry("who.api-key.enabled", "false"),
                        Map.entry("who.jwt.enabled", "false"),
                        Map.entry("who.security.last-sign-in-cookie.enabled", "false"),
                        Map.entry("who.oidc.enabled", "false"),
                        Map.entry("who.oidc.sign-up-window", "5m"),
                        Map.entry("who.oidc.providers.acme.enabled", "false"),
                        Map.entry("who.oidc.providers.acme.trust-email", "true"))))
            .bind("who", WhoProperties.class)
            .get();

    assertThat(props.getPasskey().isEnabled()).isFalse();
    assertThat(props.getEmailLink().isEnabled()).isFalse();
    assertThat(props.getEmailLink().getAddressConfirmationSubject()).isEqualTo("Confirm it");
    assertThat(props.getEmailLink().getAddressChangedSubject()).isEqualTo("It changed");
    assertThat(props.getEnrollment().isEnabled()).isFalse();
    assertThat(props.getApiKey().isEnabled()).isFalse();
    assertThat(props.getJwt().isEnabled()).isFalse();
    assertThat(props.getSecurity().getLastSignInCookie().isEnabled()).isFalse();
    assertThat(props.getOidc().isEnabled()).isFalse();
    assertThat(props.getOidc().getSignUpWindow()).isEqualTo(Duration.ofMinutes(5));
    assertThat(props.getOidc().getProviders().get("acme").isEnabled()).isFalse();
    assertThat(props.getOidc().getProviders().get("acme").getTrustEmail()).isTrue();
  }

  @Test
  void defaultsArePopulated() {
    WhoProperties props = new WhoProperties();

    assertThat(props.getInitializeSchema()).isEqualTo(DatabaseInitializationMode.EMBEDDED);
    assertThat(props.getEnrollment().getTokenExpiration()).isEqualTo(Duration.ofHours(24));
    assertThat(props.getApiKey().getHeaderName()).isEqualTo("X-API-Key");
    assertThat(props.getApiKey().getPrefix()).isEqualTo("who_");
    assertThat(props.getApiKey().getMaxLifetime()).isEqualTo(Duration.ofDays(365));
    assertThat(props.getApiKey().getLastUsedInterval()).isEqualTo(Duration.ofMinutes(5));
    assertThat(props.getMethodSecurity().isEnabled()).isTrue();
  }

  @Test
  void methodSecurityEnabledBindsFromProperties() {
    WhoProperties props =
        new Binder(
                new MapConfigurationPropertySource(Map.of("who.method-security.enabled", "false")))
            .bind("who", WhoProperties.class)
            .get();

    assertThat(props.getMethodSecurity().isEnabled()).isFalse();
  }

  @Test
  void settersRoundTrip() {
    WhoProperties props = new WhoProperties();

    props.setInitializeSchema(DatabaseInitializationMode.ALWAYS);
    assertThat(props.getInitializeSchema()).isEqualTo(DatabaseInitializationMode.ALWAYS);

    WhoProperties.Enrollment enrollment = new WhoProperties.Enrollment();
    enrollment.setTokenExpiration(Duration.ofHours(48));
    props.setEnrollment(enrollment);
    assertThat(props.getEnrollment().getTokenExpiration()).isEqualTo(Duration.ofHours(48));

    WhoProperties.ApiKey apiKey = new WhoProperties.ApiKey();
    apiKey.setHeaderName("Authorization");
    apiKey.setPrefix("acme_");
    props.setApiKey(apiKey);
    assertThat(props.getApiKey().getHeaderName()).isEqualTo("Authorization");
    assertThat(props.getApiKey().getPrefix()).isEqualTo("acme_");
  }

  @Test
  void emailLinkSubjectRoundTrips() {
    WhoProperties props = new WhoProperties();
    assertThat(props.getEmailLink().getSubject()).isEqualTo("Your sign-in link and code");

    props.getEmailLink().setSubject("Sign in to Acme");

    assertThat(props.getEmailLink().getSubject()).isEqualTo("Sign in to Acme");
  }

  @Test
  void emailLinkCodeDefaultsAndRoundTrip() {
    WhoProperties.EmailLink emailLink = new WhoProperties().getEmailLink();
    assertThat(emailLink.getCodeLength()).isEqualTo(6);
    assertThat(emailLink.getCodeMaxAttempts()).isEqualTo(5);
    assertThat(emailLink.getCodeSecret()).isNull();

    emailLink.setCodeLength(8);
    emailLink.setCodeMaxAttempts(3);
    emailLink.setCodeSecret("s");

    assertThat(emailLink.getCodeLength()).isEqualTo(8);
    assertThat(emailLink.getCodeMaxAttempts()).isEqualTo(3);
    assertThat(emailLink.getCodeSecret()).isEqualTo("s");
  }
}
