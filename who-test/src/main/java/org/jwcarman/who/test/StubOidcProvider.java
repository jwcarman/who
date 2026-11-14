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

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.util.JSONArrayUtils;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A sign-in provider for tests: an OpenID Connect provider, or GitHub, on a random local port. It
 * signs whoever {@link #nextUser} names in, with no login page: {@code /authorize} redirects
 * straight back with a code. Point a client registration at {@link #issuer()} and drive the sign-in
 * with a real browser, an HTTP client, or MockMvc.
 *
 * <pre>{@code
 * try (StubOidcProvider provider = StubOidcProvider.start()) {
 *   provider.nextUser("alice", Map.of("email", "alice@example.com", "email_verified", true));
 *   // spring.security.oauth2.client.provider.acme.issuer-uri = provider.issuer()
 * }
 * }</pre>
 *
 * <p>Requires {@code com.nimbusds:nimbus-jose-jwt}, which {@code spring-security-oauth2-jose}
 * brings.
 */
public final class StubOidcProvider implements AutoCloseable {

  private final HttpServer server;
  private final RSAKey key;
  private final boolean gitHub;
  private final String issuer;

  private volatile String subject = "user";
  private volatile Map<String, Object> claims = Map.of();
  private volatile String issuerClaim;
  private volatile String nonce;
  private volatile String clientId;
  private volatile List<Map<String, Object>> emails = List.of();

  private StubOidcProvider(boolean gitHub) {
    this.gitHub = gitHub;
    try {
      this.key = new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
      this.server =
          HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    } catch (JOSEException e) {
      throw new IllegalStateException("Cannot generate the signing key", e);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    this.issuer = "http://127.0.0.1:" + server.getAddress().getPort();
    server.createContext("/", this::handle);
    server.start();
  }

  /**
   * Starts an OpenID Connect provider: discovery at {@code /.well-known/openid-configuration},
   * {@code /authorize}, {@code /token} (with an RS256 ID token), {@code /jwks} and {@code
   * /userinfo}.
   *
   * @return the running provider
   */
  public static StubOidcProvider start() {
    return new StubOidcProvider(false);
  }

  /**
   * Starts a GitHub-like OAuth 2 provider: {@code /authorize}, {@code /token} (no ID token), {@code
   * /user} with a numeric {@code id}, and {@code /user/emails}.
   *
   * @return the running provider
   */
  public static StubOidcProvider startGitHub() {
    return new StubOidcProvider(true);
  }

  /**
   * The provider's issuer, {@code http://127.0.0.1:<port>}, which is also the base of every
   * endpoint.
   *
   * @return the issuer
   */
  public String issuer() {
    return issuer;
  }

  /**
   * Names the user the next sign-ins return.
   *
   * @param subject the {@code sub} claim, or GitHub's {@code id} (numeric when it parses as one)
   * @param claims further claims for the ID token and the user-info response
   */
  public void nextUser(String subject, Map<String, Object> claims) {
    this.subject = subject;
    this.claims = Map.copyOf(claims);
  }

  /**
   * Forges the {@code iss} claim of the next ID tokens, which are still signed by this provider's
   * key.
   *
   * @param iss the issuer claim to put in the ID token
   */
  public void nextIssuerClaim(String iss) {
    this.issuerClaim = iss;
  }

  /**
   * Sets what GitHub's {@code /user/emails} returns.
   *
   * @param emails entries with {@code email}, {@code primary} and {@code verified}
   */
  public void nextEmails(List<Map<String, Object>> emails) {
    this.emails = List.copyOf(emails);
  }

  /** Stops the server. */
  @Override
  public void close() {
    server.stop(0);
  }

  private void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      String path = exchange.getRequestURI().getPath();
      switch (path) {
        case "/.well-known/openid-configuration" -> {
          if (gitHub) {
            send(exchange, 404, "{}");
          } else {
            json(exchange, discovery());
          }
        }
        case "/jwks" -> send(exchange, 200, new JWKSet(key.toPublicJWK()).toString());
        case "/authorize" -> authorize(exchange);
        case "/token" -> json(exchange, token(exchange));
        case "/userinfo", "/user" -> json(exchange, user());
        case "/user/emails" -> send(exchange, 200, JSONArrayUtils.toJSONString(emails));
        default -> send(exchange, 404, "{}");
      }
    }
  }

  private Map<String, Object> discovery() {
    Map<String, Object> discovery = new LinkedHashMap<>();
    discovery.put("issuer", issuer);
    discovery.put("authorization_endpoint", issuer + "/authorize");
    discovery.put("token_endpoint", issuer + "/token");
    discovery.put("jwks_uri", issuer + "/jwks");
    discovery.put("userinfo_endpoint", issuer + "/userinfo");
    discovery.put("response_types_supported", List.of("code"));
    discovery.put("subject_types_supported", List.of("public"));
    discovery.put("id_token_signing_alg_values_supported", List.of("RS256"));
    discovery.put("scopes_supported", List.of("openid", "email", "profile"));
    return discovery;
  }

  private void authorize(HttpExchange exchange) throws IOException {
    Map<String, String> query = form(exchange.getRequestURI().getRawQuery());
    this.nonce = query.get("nonce");
    this.clientId = query.get("client_id");
    String location =
        query.get("redirect_uri")
            + "?code="
            + UUID.randomUUID()
            + "&state="
            + URLEncoder.encode(query.getOrDefault("state", ""), StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Location", location);
    exchange.sendResponseHeaders(302, -1);
  }

  private Map<String, Object> token(HttpExchange exchange) throws IOException {
    Map<String, String> form =
        form(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    Map<String, Object> token = new LinkedHashMap<>();
    token.put("access_token", UUID.randomUUID().toString());
    token.put("token_type", "Bearer");
    token.put("expires_in", 300);
    if (!gitHub) {
      token.put("scope", "openid email profile");
      token.put("id_token", idToken(audience(exchange, form)));
    }
    return token;
  }

  private String audience(HttpExchange exchange, Map<String, String> form) {
    String basic = exchange.getRequestHeaders().getFirst("Authorization");
    if (basic != null && basic.startsWith("Basic ")) {
      String decoded =
          new String(Base64.getDecoder().decode(basic.substring(6)), StandardCharsets.UTF_8);
      return URLDecoder.decode(decoded.substring(0, decoded.indexOf(':')), StandardCharsets.UTF_8);
    }
    return form.getOrDefault("client_id", clientId);
  }

  private String idToken(String audience) {
    Instant now = Instant.now();
    JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder();
    claims.forEach(builder::claim);
    builder
        .issuer(issuerClaim != null ? issuerClaim : issuer)
        .subject(subject)
        .audience(audience)
        .issueTime(Date.from(now))
        .expirationTime(Date.from(now.plusSeconds(300)));
    if (nonce != null) {
      builder.claim("nonce", nonce);
    }
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
            builder.build());
    try {
      jwt.sign(new RSASSASigner(key));
    } catch (JOSEException e) {
      throw new IllegalStateException("Cannot sign the ID token", e);
    }
    return jwt.serialize();
  }

  private Map<String, Object> user() {
    Map<String, Object> user = new HashMap<>(claims);
    if (gitHub) {
      user.put("id", numericIfPossible(subject));
    } else {
      user.put("sub", subject);
    }
    return user;
  }

  private static Object numericIfPossible(String value) {
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      return value;
    }
  }

  private static Map<String, String> form(String encoded) {
    Map<String, String> values = new HashMap<>();
    if (encoded == null || encoded.isEmpty()) {
      return values;
    }
    for (String pair : encoded.split("&")) {
      int eq = pair.indexOf('=');
      String name = eq < 0 ? pair : pair.substring(0, eq);
      String value = eq < 0 ? "" : pair.substring(eq + 1);
      values.put(
          URLDecoder.decode(name, StandardCharsets.UTF_8),
          URLDecoder.decode(value, StandardCharsets.UTF_8));
    }
    return values;
  }

  private static void json(HttpExchange exchange, Map<String, Object> body) throws IOException {
    send(exchange, 200, JSONObjectUtils.toJSONString(body));
  }

  private static void send(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }
}
