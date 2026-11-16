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

import org.jwcarman.who.spring.security.WhoHumanCheck;
import org.jwcarman.who.turnstile.TurnstileHumanCheck;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * Cloudflare Turnstile as who's human check, on with the {@code who-turnstile} jar. Startup fails
 * without {@code who.turnstile.secret-key}; {@code who.turnstile.enabled=false} turns it off. An
 * application's own {@link WhoHumanCheck} bean wins.
 */
@AutoConfiguration
@EnableConfigurationProperties(WhoProperties.class)
@ConditionalOnClass(name = "org.jwcarman.who.turnstile.TurnstileHumanCheck")
@ConditionalOnBooleanProperty(name = "who.turnstile.enabled", matchIfMissing = true)
public class WhoTurnstileAutoConfiguration {

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
   * @return the check
   */
  @Bean
  @ConditionalOnMissingBean(WhoHumanCheck.class)
  public TurnstileHumanCheck turnstileHumanCheck(WhoProperties whoProperties) {
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
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(properties.getTimeout());
    requestFactory.setReadTimeout(properties.getTimeout());
    return new TurnstileHumanCheck(
        RestClient.builder().requestFactory(requestFactory).build(),
        secretKey.strip(),
        properties.getHostnames(),
        properties.isFailOpen());
  }
}
