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

import java.util.Map;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.signup.ClosedSignUpPolicy;
import org.jwcarman.who.core.signup.SignUpDecision;
import org.jwcarman.who.core.signup.WhoSignUpPolicy;
import org.jwcarman.who.oidc.OidcLoginMethod;
import org.jwcarman.who.oidc.PendingSignUpFilter;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class WhoOidcAutoConfigurationTest extends AbstractOidcTest {

  @Nested
  class In_the_default_chain {

    @Test
    void with_a_registration_mounts_oauth2_login_in_the_default_chain() {
      SecurityFilterChain chain =
          context.getBean("whoSecurityFilterChain", SecurityFilterChain.class);

      assertThat(context.getBeansOfType(OidcLoginMethod.class)).hasSize(1);
      assertThat(chain.getFilters())
          .extracting(Filter::getClass)
          .contains(OAuth2LoginAuthenticationFilter.class, PendingSignUpFilter.class);
    }

    @Test
    void boots_oauth2_login_chain_is_replaced() {
      Map<String, SecurityFilterChain> chains = context.getBeansOfType(SecurityFilterChain.class);

      assertThat(chains).containsOnlyKeys("whoSecurityFilterChain");
    }

    @Test
    void an_application_oauth2_authorized_client_repository_is_untouched() throws Exception {
      signInAsAlice();

      assertThat(me()).isEqualTo(200);
      assertThat(context.getBean(OAuth2AuthorizedClientRepository.class))
          .isSameAs(recordingAuthorizedClients);
      assertThat(recordingAuthorizedClients.calls).hasValue(0);
    }
  }

  @Nested
  class Beans {

    private final ApplicationContextRunner runner =
        new ApplicationContextRunner()
            .withConfiguration(
                WhoTestAutoConfigurations.who(
                    OAuth2ClientAutoConfiguration.class,
                    DataSourceTransactionManagerAutoConfiguration.class))
            .withUserConfiguration(ModuleActivationTest.Database.class)
            .withPropertyValues(
                "who.oidc.enabled=true",
                "who.email-link.enabled=false",
                "who.passkey.enabled=false",
                "spring.security.oauth2.client.registration.github.client-id=client",
                "spring.security.oauth2.client.registration.github.client-secret=secret");

    @Test
    void default_policy_is_closed() {
      runner.run(
          context ->
              assertThat(context.getBean(WhoSignUpPolicy.class))
                  .isInstanceOf(ClosedSignUpPolicy.class));
    }

    @Test
    void an_application_policy_replaces_it() {
      runner
          .withBean(WhoSignUpPolicy.class, () -> request -> SignUpDecision.allow())
          .run(
              context -> {
                assertThat(context).hasSingleBean(WhoSignUpPolicy.class);
                assertThat(context).doesNotHaveBean(ClosedSignUpPolicy.class);
              });
    }

    @Test
    void an_explicit_redirect_uri_under_the_root_starts() {
      runner
          .withPropertyValues(
              "spring.security.oauth2.client.registration.github.redirect-uri="
                  + "{baseUrl}/who/oidc/callback/{registrationId}")
          .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void an_explicit_redirect_uri_who_does_not_serve_fails_startup() {
      runner
          .withPropertyValues(
              "spring.security.oauth2.client.registration.github.redirect-uri="
                  + "{baseUrl}/login/oauth2/code/{registrationId}")
          .run(
              context ->
                  assertThat(context)
                      .getFailure()
                      .hasStackTraceContaining(
                          "spring.security.oauth2.client.registration.github.redirect-uri")
                      .hasStackTraceContaining("/who/oidc/callback/{registrationId}"));
    }

    @Test
    void oidc_disabled_registers_nothing() {
      runner
          .withPropertyValues("who.oidc.enabled=false")
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(OidcLoginMethod.class);
                assertThat(context).doesNotHaveBean(WhoSignUpPolicy.class);
              });
    }
  }
}
