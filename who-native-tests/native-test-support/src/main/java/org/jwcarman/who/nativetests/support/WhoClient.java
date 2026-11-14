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
package org.jwcarman.who.nativetests.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A client of one app, like one browser: it keeps cookies, and sends the {@code XSRF-TOKEN}
 * cookie's value as {@code X-XSRF-TOKEN} on every request that is not a GET, the way who's guides
 * tell a page to. When it has no token (the first request, or after sign-out cleared it) it does
 * {@code GET /who/me} first to get one.
 */
public final class WhoClient implements AutoCloseable {

  private static final JsonMapper JSON = new JsonMapper();
  private static final String CSRF_COOKIE = "XSRF-TOKEN";

  private final URI baseUri;
  private final CookieManager cookies = new CookieManager();
  private final HttpClient http;
  private final boolean csrf;

  /**
   * Creates a client with no cookies, for an app with who's browser chain.
   *
   * @param baseUri the app's base URI
   */
  public WhoClient(URI baseUri) {
    this(baseUri, true);
  }

  private WhoClient(URI baseUri, boolean csrf) {
    this.baseUri = baseUri;
    this.csrf = csrf;
    this.http = HttpClient.newBuilder().cookieHandler(cookies).build();
  }

  /**
   * Creates a client that sends no CSRF token, for a stateless app (API keys, JWTs).
   *
   * @param baseUri the app's base URI
   * @return the client
   */
  public static WhoClient stateless(URI baseUri) {
    return new WhoClient(baseUri, false);
  }

  /** An HTTP answer. */
  public record Response(int status, Map<String, List<String>> headers, String body) {

    /**
     * The body as JSON.
     *
     * @return the parsed body
     */
    public JsonNode json() {
      return JSON.readTree(body);
    }
  }

  /**
   * Sends a GET.
   *
   * @param path the path, such as {@code /who/me}
   * @param headers extra headers as name, value pairs
   * @return the answer
   */
  public Response get(String path, String... headers) {
    return send("GET", path, null, headers);
  }

  /**
   * Sends a POST with a JSON body, or none when {@code json} is null.
   *
   * @param path the path
   * @param json the body
   * @param headers extra headers as name, value pairs
   * @return the answer
   */
  public Response post(String path, String json, String... headers) {
    return send("POST", path, json, headers);
  }

  /**
   * Sends a DELETE.
   *
   * @param path the path
   * @return the answer
   */
  public Response delete(String path) {
    return send("DELETE", path, null);
  }

  /**
   * Turns a value into JSON.
   *
   * @param value a map, list or string
   * @return the JSON text
   */
  public static String json(Object value) {
    return JSON.writeValueAsString(value);
  }

  private Response send(String method, String path, String body, String... headers) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(baseUri.resolve(path)).timeout(Duration.ofSeconds(30));
    if (csrf && !"GET".equals(method)) {
      String token =
          csrfToken()
              .orElseGet(
                  () -> {
                    send("GET", "/who/me", null);
                    return csrfToken()
                        .orElseThrow(
                            () -> new IllegalStateException("The app set no XSRF-TOKEN cookie"));
                  });
      request.header("X-XSRF-TOKEN", token);
    }
    if (body != null) {
      request.header("Content-Type", "application/json");
    }
    for (int i = 0; i + 1 < headers.length; i += 2) {
      request.header(headers[i], headers[i + 1]);
    }
    request.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body));
    try {
      HttpResponse<String> response =
          http.send(request.build(), HttpResponse.BodyHandlers.ofString());
      return new Response(response.statusCode(), response.headers().map(), response.body());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private Optional<String> csrfToken() {
    return cookies.getCookieStore().get(baseUri).stream()
        .filter(c -> CSRF_COOKIE.equals(c.getName()))
        .map(HttpCookie::getValue)
        .map(v -> URLDecoder.decode(v, StandardCharsets.UTF_8))
        .findFirst();
  }

  @Override
  public void close() {
    http.close();
  }
}
