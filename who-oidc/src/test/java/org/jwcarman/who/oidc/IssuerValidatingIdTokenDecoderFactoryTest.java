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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.ParseException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.test.StubOidcProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;

import com.nimbusds.jose.util.JSONObjectUtils;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class IssuerValidatingIdTokenDecoderFactoryTest {

  private StubOidcProvider provider;
  private ClientRegistration registration;
  private JwtDecoder decoder;

  @BeforeEach
  void setUp() {
    provider = StubOidcProvider.start();
    registration =
        ClientRegistration.withRegistrationId("acme")
            .clientId("client")
            .clientSecret("secret")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
            .scope("openid")
            .authorizationUri(provider.issuer() + "/authorize")
            .tokenUri(provider.issuer() + "/token")
            .jwkSetUri(provider.issuer() + "/jwks")
            .userInfoUri(provider.issuer() + "/userinfo")
            .userNameAttributeName("sub")
            .build();
    SignInRegistrations registrations =
        SignInRegistrations.of(
            List.of(registration),
            Map.of("acme", new ProviderSettings(true, provider.issuer(), null, null, null)));
    decoder = new IssuerValidatingIdTokenDecoderFactory(registrations).createDecoder(registration);
  }

  @AfterEach
  void tearDown() {
    provider.close();
  }

  @Test
  void matching_iss_decodes() throws Exception {
    provider.nextUser("alice", Map.of("email", "alice@example.com"));

    Jwt jwt = decoder.decode(idToken());

    assertThat(jwt.getSubject()).isEqualTo("alice");
    assertThat(jwt.getIssuer()).hasToString(provider.issuer());
  }

  @Test
  void endpoint_registration_rejects_a_foreign_iss() throws Exception {
    provider.nextUser("alice", Map.of());
    provider.nextIssuerClaim("https://accounts.google.com");
    String token = idToken();

    assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtValidationException.class);
  }

  private String idToken() throws IOException, InterruptedException, ParseException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(provider.issuer() + "/token"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "grant_type=authorization_code&code=any&client_id=client"))
            .build();
    try (HttpClient client = HttpClient.newHttpClient()) {
      String body = client.send(request, HttpResponse.BodyHandlers.ofString()).body();
      return JSONObjectUtils.getString(JSONObjectUtils.parse(body), "id_token");
    }
  }
}
