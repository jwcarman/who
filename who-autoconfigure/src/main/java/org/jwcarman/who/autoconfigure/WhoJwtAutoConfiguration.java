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

import java.time.Clock;
import java.util.List;

import org.jwcarman.who.core.event.WhoEventPublisher;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.federated.FederatedCredentialRepository;
import org.jwcarman.who.jwt.JwtLoginMethod;
import org.jwcarman.who.jwt.WhoJwtAuthenticationConverter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.util.StringUtils;

/** Registers JWT beans when {@code who-jwt} is on the classpath. */
@AutoConfiguration(after = {WhoAutoConfiguration.class, WhoFederatedAutoConfiguration.class})
@EnableConfigurationProperties(WhoProperties.class)
@ConditionalOnClass(name = "org.jwcarman.who.jwt.WhoJwtAuthenticationConverter")
@ConditionalOnBooleanProperty(name = "who.jwt.enabled", matchIfMissing = true)
public class WhoJwtAutoConfiguration {

  private static final String JWT_PROPERTIES = "spring.security.oauth2.resourceserver.jwt.";
  private static final String AUDIENCES = JWT_PROPERTIES + "audiences";
  private static final List<String> DECODER_PROPERTIES =
      List.of(
          JWT_PROPERTIES + "issuer-uri",
          JWT_PROPERTIES + "jwk-set-uri",
          JWT_PROPERTIES + "public-key-location");

  /** Creates the autoconfiguration; Spring Boot calls it. */
  public WhoJwtAutoConfiguration() {
    // nothing to set up: every bean is built by a method below
  }

  /**
   * The converter that signs JWTs in, publishing the sign-in events.
   *
   * @param federatedCredentialRepository looks credentials up by issuer and subject
   * @param whoService resolves a credential to its identity
   * @param whoEventPublisher where the sign-in events are published
   * @param clock stamps the events
   * @return the converter
   */
  @Bean
  @ConditionalOnMissingBean
  public WhoJwtAuthenticationConverter whoJwtAuthenticationConverter(
      FederatedCredentialRepository federatedCredentialRepository,
      WhoService whoService,
      WhoEventPublisher whoEventPublisher,
      Clock clock) {
    return new WhoJwtAuthenticationConverter(
        federatedCredentialRepository, whoService, whoEventPublisher, clock);
  }

  /**
   * Mounts JWT sign-in in a chain built with {@code WhoSecurity.all()}. The {@link JwtDecoder} is
   * looked up when the chain is built: Spring Boot makes one from {@code
   * spring.security.oauth2.resourceserver.jwt.issuer-uri} or {@code jwk-set-uri}, and without one
   * the chain fails to start.
   *
   * @param converter signs a verified JWT in
   * @param decoders the application's decoder
   * @param environment where Spring Boot's resource-server properties are read
   * @return the login method
   */
  @Bean
  @ConditionalOnMissingBean
  public JwtLoginMethod jwtLoginMethod(
      WhoJwtAuthenticationConverter converter,
      ObjectProvider<JwtDecoder> decoders,
      Environment environment) {
    return new JwtLoginMethod(
        converter,
        () -> {
          requireAudiences(environment);
          return decoders.getIfAvailable(
              () -> {
                throw new WhoConfigurationException(
                    "spring.security.oauth2.resourceserver.jwt.issuer-uri",
                    "is not set and there is no JwtDecoder bean, so JWTs cannot be verified",
                    "Set it, or spring.security.oauth2.resourceserver.jwt.jwk-set-uri, or register"
                        + " a JwtDecoder bean. Or turn JWT sign-in off with who.jwt.enabled=false.");
              });
        });
  }

  /**
   * Fails when Spring Boot builds the decoder from its properties but no audience is configured:
   * Spring's resource server does not check {@code aud} by default, so a token the same issuer
   * minted for any other application, such as a provider's ID token, would sign in. A decoder the
   * application registers itself owns that check.
   */
  private static void requireAudiences(Environment environment) {
    boolean bootBuildsTheDecoder =
        DECODER_PROPERTIES.stream().map(environment::getProperty).anyMatch(StringUtils::hasText);
    boolean audiencesSet =
        Binder.get(environment)
            .bind(AUDIENCES, Bindable.listOf(String.class))
            .map(audiences -> audiences.stream().anyMatch(StringUtils::hasText))
            .orElse(false);
    if (bootBuildsTheDecoder && !audiencesSet) {
      throw new WhoConfigurationException(
          AUDIENCES,
          "is not set, so who-jwt would accept a token issued to any audience",
          "Set it to the audience your API's tokens carry. Or turn JWT sign-in off with"
              + " who.jwt.enabled=false.");
    }
  }
}
