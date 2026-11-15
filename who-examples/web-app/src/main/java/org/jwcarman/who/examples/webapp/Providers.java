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
package org.jwcarman.who.examples.webapp;

import java.util.ArrayList;
import java.util.List;

import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.stereotype.Component;

/**
 * The identity providers configured under {@code spring.security.oauth2.client.registration}, for
 * the sign-in and link buttons. Only providers that are configured get a button.
 */
@Component
class Providers {

  /**
   * A provider's button.
   *
   * @param id the registration id, as in {@code /oauth2/authorization/{id}}
   * @param name the name to show
   */
  record Provider(String id, String name) {}

  private final List<Provider> all;

  Providers(ClientRegistrationRepository registrations) {
    List<Provider> found = new ArrayList<>();
    if (registrations instanceof Iterable<?> iterable) {
      for (Object each : iterable) {
        if (each instanceof ClientRegistration registration
            && AuthorizationGrantType.AUTHORIZATION_CODE.equals(
                registration.getAuthorizationGrantType())) {
          found.add(new Provider(registration.getRegistrationId(), registration.getClientName()));
        }
      }
    }
    this.all = List.copyOf(found);
  }

  List<Provider> all() {
    return all;
  }
}
