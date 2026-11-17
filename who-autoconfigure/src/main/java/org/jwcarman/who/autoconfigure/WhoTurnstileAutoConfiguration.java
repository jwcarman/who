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

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;

import org.jwcarman.who.spring.security.WhoHumanCheck;
import org.jwcarman.who.turnstile.TurnstileHumanCheck;
import org.jwcarman.who.turnstile.TurnstileVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * Cloudflare Turnstile as who's human check, on with the {@code who-turnstile} jar. Startup fails
 * without {@code who.turnstile.secret-key}; {@code who.turnstile.enabled=false} turns it off. An
 * application's own {@link WhoHumanCheck} bean wins.
 *
 * <p>An application's {@link TurnstileVerifier} bean decides what Cloudflare's answers pass.
 * Without one, a Cloudflare test secret key passes on success alone, with a warning at startup, and
 * any other key gets the strict {@link TurnstileVerifier}.
 *
 * <p>The client is built from Spring Boot's {@link RestClient.Builder} and the application's HTTP
 * client settings, which {@code who-turnstile} brings; only the timeouts are who's.
 */
// named, not referenced: these come with who-turnstile, which may be absent
@AutoConfiguration(
    afterName = {
      "org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration",
      "org.springframework.boot.http.client.autoconfigure.imperative.ImperativeHttpClientAutoConfiguration",
      "org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration"
    })
@EnableConfigurationProperties(WhoProperties.class)
@ConditionalOnClass(name = "org.jwcarman.who.turnstile.TurnstileHumanCheck")
@ConditionalOnBooleanProperty(name = "who.turnstile.enabled", matchIfMissing = true)
public class WhoTurnstileAutoConfiguration {

  private static final Logger log = LoggerFactory.getLogger(WhoTurnstileAutoConfiguration.class);

  private static final String SECRET_KEY = "who.turnstile.secret-key";
  private static final String TURN_OFF = " Or turn the check off with who.turnstile.enabled=false.";

  /** Creates the autoconfiguration; Spring Boot calls it. */
  public WhoTurnstileAutoConfiguration() {
    // nothing to set up: the bean is built by the method below
  }

  /**
   * The Turnstile check. Startup fails when the secret key is missing or an unresolved placeholder.
   *
   * @param whoProperties who's properties
   * @param verifiers the application's verifier, if it has one
   * @param restClientBuilder Spring Boot's {@link RestClient.Builder}, or the application's, with
   *     its observations and customizers; copied, then given who's request factory
   * @param requestFactories builds request factories with the application's HTTP client library
   * @param httpClientSettings the application's HTTP client settings, which who keeps but for the
   *     timeouts
   * @return the check
   */
  @Bean
  @ConditionalOnMissingBean(WhoHumanCheck.class)
  public TurnstileHumanCheck turnstileHumanCheck(
      WhoProperties whoProperties,
      ObjectProvider<TurnstileVerifier> verifiers,
      RestClient.Builder restClientBuilder,
      ClientHttpRequestFactoryBuilder<?> requestFactories,
      HttpClientSettings httpClientSettings) {
    WhoProperties.Turnstile properties = whoProperties.getTurnstile();
    String secretKey = properties.getSecretKey();
    if (!StringUtils.hasText(secretKey)) {
      throw new WhoConfigurationException(
          SECRET_KEY,
          "must be set to the Turnstile widget's secret key",
          "Set it from the Cloudflare dashboard, such as ${TURNSTILE_SECRET}." + TURN_OFF);
    }
    // Spring binds an unresolvable ${VAR} as literal text, which would be sent to Cloudflare
    if (secretKey.contains("${")) {
      throw new WhoConfigurationException(
          SECRET_KEY,
          "is an unresolved placeholder",
          "Set the environment variable or property it names." + TURN_OFF);
    }
    Duration timeout = properties.getTimeout();
    // zero, or a negative value the request factory ignores, would wait on Cloudflare forever
    if (timeout == null || !timeout.isPositive()) {
      throw new WhoConfigurationException(
          "who.turnstile.timeout",
          "must be longer than zero",
          "Set how long to wait for Cloudflare, such as 3s, or leave it out.");
    }
    URI verifyUrl = verifyUrl(properties.getVerifyUrl());
    // the application's settings, all but the timeouts, which are who's to bound a sign-in
    ClientHttpRequestFactory requestFactory =
        requestFactories.build(httpClientSettings.withTimeouts(timeout, timeout));
    String secret = secretKey.strip();
    return new TurnstileHumanCheck(
        restClientBuilder
            .clone()
            // who.turnstile.timeout must hold, so who's request factory replaces the builder's
            .requestFactory(requestFactory)
            .build(),
        verifyUrl,
        secret,
        verifiers.getIfAvailable(() -> defaultVerifier(secret, properties)),
        properties.isFailOpen());
  }

  /** Success alone for a Cloudflare test key, with a warning; strict for any other. */
  private static TurnstileVerifier defaultVerifier(
      String secretKey, WhoProperties.Turnstile properties) {
    if (TurnstileVerifier.isTestSecret(secretKey)) {
      log.warn(
          "who.turnstile.secret-key is one of Cloudflare's test keys: Cloudflare decides every"
              + " request by the key, not by the person. Never use it in production.");
      return TurnstileVerifier.successOnly();
    }
    return TurnstileVerifier.strict(properties.getHostnames());
  }

  /** The verify URL, which must be an absolute http or https URL. */
  private static URI verifyUrl(String value) {
    URI uri = null;
    if (StringUtils.hasText(value)) {
      try {
        uri = new URI(value.strip());
      } catch (URISyntaxException _) {
        // reported below
      }
    }
    if (uri == null
        || uri.getHost() == null
        || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))) {
      throw new WhoConfigurationException(
          "who.turnstile.verify-url",
          "must be an absolute http or https URL",
          "Leave it out to use Cloudflare's siteverify, or point it at a stand-in in tests.");
    }
    return uri;
  }
}
