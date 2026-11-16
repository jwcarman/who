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
package org.jwcarman.who.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.SignedJWT;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class StubOidcProviderTest {

  private static final HttpClient HTTP =
      HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

  private static HttpResponse<String> get(String url) throws IOException, InterruptedException {
    return HTTP.send(
        HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
  }

  private static Map<String, Object> token(StubOidcProvider provider)
      throws IOException, InterruptedException, ParseException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(provider.issuer() + "/token"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "grant_type=authorization_code&code=any&client_id=client"))
            .build();
    return JSONObjectUtils.parse(HTTP.send(request, HttpResponse.BodyHandlers.ofString()).body());
  }

  @Nested
  class Oidc {

    @Test
    void discovery_names_the_issuer_and_its_endpoints() throws Exception {
      try (StubOidcProvider provider = StubOidcProvider.start()) {
        Map<String, Object> discovery =
            JSONObjectUtils.parse(
                get(provider.issuer() + "/.well-known/openid-configuration").body());

        assertThat(discovery)
            .containsEntry("issuer", provider.issuer())
            .containsEntry("jwks_uri", provider.issuer() + "/jwks")
            .containsEntry("token_endpoint", provider.issuer() + "/token");
      }
    }

    @Test
    void authorize_redirects_back_with_the_state() throws Exception {
      try (StubOidcProvider provider = StubOidcProvider.start()) {
        HttpResponse<String> response =
            get(
                provider.issuer()
                    + "/authorize?response_type=code&client_id=client&state=xyz&nonce=n1"
                    + "&redirect_uri=http%3A%2F%2Flocalhost%2Fcallback");

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location").orElseThrow())
            .startsWith("http://localhost/callback?code=")
            .endsWith("&state=xyz");
      }
    }

    @Test
    void the_id_token_is_signed_by_the_jwks_key_and_echoes_the_nonce() throws Exception {
      try (StubOidcProvider provider = StubOidcProvider.start()) {
        provider.nextUser("alice", Map.of("email", "alice@example.com"));
        get(
            provider.issuer()
                + "/authorize?client_id=client&state=s&nonce=n1&redirect_uri=http%3A%2F%2Fx");

        SignedJWT idToken = SignedJWT.parse((String) token(provider).get("id_token"));
        RSAKey key =
            (RSAKey) JWKSet.parse(get(provider.issuer() + "/jwks").body()).getKeys().get(0);

        assertThat(idToken.verify(new RSASSAVerifier(key))).isTrue();
        assertThat(idToken.getJWTClaimsSet().getIssuer()).isEqualTo(provider.issuer());
        assertThat(idToken.getJWTClaimsSet().getSubject()).isEqualTo("alice");
        assertThat(idToken.getJWTClaimsSet().getAudience()).containsExactly("client");
        assertThat(idToken.getJWTClaimsSet().getStringClaim("nonce")).isEqualTo("n1");
        assertThat(idToken.getJWTClaimsSet().getStringClaim("email"))
            .isEqualTo("alice@example.com");
      }
    }

    @Test
    void a_forged_issuer_claim_is_signed_anyway() throws Exception {
      try (StubOidcProvider provider = StubOidcProvider.start()) {
        provider.nextUser("alice", Map.of());
        provider.nextIssuerClaim("https://accounts.google.com");

        SignedJWT idToken = SignedJWT.parse((String) token(provider).get("id_token"));

        assertThat(idToken.getJWTClaimsSet().getIssuer()).isEqualTo("https://accounts.google.com");
      }
    }

    @Test
    void a_client_authenticating_with_basic_gets_an_id_token_for_itself() throws Exception {
      try (StubOidcProvider provider = StubOidcProvider.start()) {
        provider.nextUser("alice", Map.of());
        String basic =
            Base64.getEncoder().encodeToString("web%20app:secret".getBytes(StandardCharsets.UTF_8));
        HttpRequest request =
            HttpRequest.newBuilder(URI.create(provider.issuer() + "/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Authorization", "Basic " + basic)
                .POST(HttpRequest.BodyPublishers.ofString("grant_type&code=any"))
                .build();

        Map<String, Object> token =
            JSONObjectUtils.parse(HTTP.send(request, HttpResponse.BodyHandlers.ofString()).body());

        assertThat(SignedJWT.parse((String) token.get("id_token")).getJWTClaimsSet().getAudience())
            .containsExactly("web app");
      }
    }

    @Test
    void a_token_request_with_no_form_is_for_the_client_that_authorized() throws Exception {
      try (StubOidcProvider provider = StubOidcProvider.start()) {
        provider.nextUser("alice", Map.of());
        get(provider.issuer() + "/authorize?client_id=cli&state=s&redirect_uri=http%3A%2F%2Fx");
        HttpRequest request =
            HttpRequest.newBuilder(URI.create(provider.issuer() + "/token"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        Map<String, Object> token =
            JSONObjectUtils.parse(HTTP.send(request, HttpResponse.BodyHandlers.ofString()).body());

        assertThat(SignedJWT.parse((String) token.get("id_token")).getJWTClaimsSet().getAudience())
            .containsExactly("cli");
      }
    }

    @Test
    void a_client_sending_another_kind_of_authorization_is_named_by_its_form() throws Exception {
      try (StubOidcProvider provider = StubOidcProvider.start()) {
        provider.nextUser("alice", Map.of());
        HttpRequest request =
            HttpRequest.newBuilder(URI.create(provider.issuer() + "/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Authorization", "Bearer something")
                .POST(HttpRequest.BodyPublishers.ofString("client_id=from-form&code=any"))
                .build();

        Map<String, Object> token =
            JSONObjectUtils.parse(HTTP.send(request, HttpResponse.BodyHandlers.ofString()).body());

        assertThat(SignedJWT.parse((String) token.get("id_token")).getJWTClaimsSet().getAudience())
            .containsExactly("from-form");
      }
    }

    @Test
    void authorize_without_a_query_is_400() throws Exception {
      try (StubOidcProvider provider = StubOidcProvider.start()) {
        assertThat(get(provider.issuer() + "/authorize").statusCode()).isEqualTo(400);
      }
    }

    @Test
    void authorize_without_a_redirect_uri_is_400() throws Exception {
      try (StubOidcProvider provider = StubOidcProvider.start()) {
        assertThat(get(provider.issuer() + "/authorize?client_id=client&state=s").statusCode())
            .isEqualTo(400);
      }
    }

    @Test
    void an_unknown_path_is_404() throws Exception {
      try (StubOidcProvider provider = StubOidcProvider.start()) {
        assertThat(get(provider.issuer() + "/nowhere").statusCode()).isEqualTo(404);
      }
    }

    @Test
    void userinfo_returns_the_user() throws Exception {
      try (StubOidcProvider provider = StubOidcProvider.start()) {
        provider.nextUser("alice", Map.of("email", "alice@example.com"));

        Map<String, Object> userInfo =
            JSONObjectUtils.parse(get(provider.issuer() + "/userinfo").body());

        assertThat(userInfo)
            .containsEntry("sub", "alice")
            .containsEntry("email", "alice@example.com");
      }
    }
  }

  @Nested
  class GitHub {

    @Test
    void the_token_has_no_id_token_and_user_has_the_numeric_id() throws Exception {
      try (StubOidcProvider provider = StubOidcProvider.startGitHub()) {
        provider.nextUser("583231", Map.of("login", "octocat"));

        assertThat(token(provider)).containsKey("access_token").doesNotContainKey("id_token");
        Map<String, Object> user = JSONObjectUtils.parse(get(provider.issuer() + "/user").body());
        assertThat(user).containsEntry("login", "octocat");
        assertThat(((Number) user.get("id")).longValue()).isEqualTo(583231L);
      }
    }

    @Test
    void there_is_no_openid_discovery() throws Exception {
      try (StubOidcProvider provider = StubOidcProvider.startGitHub()) {
        assertThat(get(provider.issuer() + "/.well-known/openid-configuration").statusCode())
            .isEqualTo(404);
      }
    }

    @Test
    void a_subject_that_is_not_a_number_is_served_as_text() throws Exception {
      try (StubOidcProvider provider = StubOidcProvider.startGitHub()) {
        provider.nextUser("octocat", Map.of());

        Map<String, Object> user = JSONObjectUtils.parse(get(provider.issuer() + "/user").body());

        assertThat(user).containsEntry("id", "octocat");
      }
    }

    @Test
    void user_emails_serves_the_configured_emails() throws Exception {
      try (StubOidcProvider provider = StubOidcProvider.startGitHub()) {
        provider.nextEmails(
            List.of(Map.of("email", "octocat@github.com", "primary", true, "verified", true)));

        String body = get(provider.issuer() + "/user/emails").body();

        assertThat(JSONObjectUtils.parse("{\"e\":" + body + "}").get("e")).asList().hasSize(1);
      }
    }
  }
}
