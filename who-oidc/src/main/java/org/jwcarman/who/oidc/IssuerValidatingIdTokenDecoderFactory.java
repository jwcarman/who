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
package org.jwcarman.who.oidc;

import java.util.List;

import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;

/**
 * Decodes ID tokens with Spring Security's validation plus one more check: {@code iss} must be the
 * issuer who stores the registration's credentials under. Spring checks {@code iss} only when the
 * provider is configured with {@code issuer-uri}; without this, a provider configured by its
 * endpoints could sign a token claiming any issuer, and so any other provider's accounts.
 */
public final class IssuerValidatingIdTokenDecoderFactory
    implements JwtDecoderFactory<ClientRegistration> {

  private final OidcIdTokenDecoderFactory delegate = new OidcIdTokenDecoderFactory();

  /**
   * Creates the factory.
   *
   * @param registrations the registrations, each with its issuer
   */
  public IssuerValidatingIdTokenDecoderFactory(SignInRegistrations registrations) {
    delegate.setJwtValidatorFactory(
        client ->
            JwtValidators.createDefaultWithValidators(
                List.of(
                    new OidcIdTokenValidator(client),
                    new JwtIssuerValidator(issuer(registrations, client)))));
  }

  @Override
  public JwtDecoder createDecoder(ClientRegistration clientRegistration) {
    return delegate.createDecoder(clientRegistration);
  }

  private static String issuer(SignInRegistrations registrations, ClientRegistration client) {
    return registrations
        .find(client.getRegistrationId())
        .map(SignInRegistration::issuer)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "Client registration '"
                        + client.getRegistrationId()
                        + "' is not one who signs in with"));
  }
}
