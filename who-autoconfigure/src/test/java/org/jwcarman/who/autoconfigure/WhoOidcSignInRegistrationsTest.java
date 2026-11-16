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

import java.util.Iterator;
import java.util.List;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.oidc.SignInRegistrations;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

/** The registrations who signs in with, read from the application's repository. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class WhoOidcSignInRegistrationsTest {

  private static final ClientRegistration ACME =
      ClientRegistration.withRegistrationId("acme")
          .clientId("client")
          .clientSecret("secret")
          .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
          .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
          .scope("openid")
          .issuerUri("https://idp.example.com")
          .authorizationUri("https://idp.example.com/authorize")
          .tokenUri("https://idp.example.com/token")
          .jwkSetUri("https://idp.example.com/jwks")
          .userNameAttributeName("sub")
          .build();

  /** A repository that, iterated, yields something other than registrations too. */
  static final class MixedRepository implements ClientRegistrationRepository, Iterable<Object> {
    @Override
    public ClientRegistration findByRegistrationId(String registrationId) {
      return "acme".equals(registrationId) ? ACME : null;
    }

    @Override
    public Iterator<Object> iterator() {
      return List.<Object>of(ACME, "not a registration").iterator();
    }
  }

  @Test
  void a_repository_yielding_other_things_contributes_only_its_registrations() {
    StaticListableBeanFactory beans = new StaticListableBeanFactory();
    beans.addBean("clientRegistrationRepository", new MixedRepository());

    SignInRegistrations registrations =
        new WhoOidcAutoConfiguration()
            .signInRegistrations(
                beans.getBeanProvider(ClientRegistrationRepository.class), new WhoProperties());

    assertThat(registrations.find("acme")).isPresent();
  }
}
