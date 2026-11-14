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
package org.jwcarman.who.oidc.aot;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.oidc.PendingSignUp;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class OidcRuntimeHintsTest {

  @Test
  void a_pending_sign_up_can_be_serialized_into_a_session_store() {
    RuntimeHints hints = new RuntimeHints();
    new OidcRuntimeHints().registerHints(hints, getClass().getClassLoader());

    for (Class<?> type :
        new Class<?>[] {
          PendingSignUp.class,
          UUID.class,
          Instant.class,
          String.class,
          Boolean.class,
          Long.class,
          Integer.class,
          Double.class,
          Number.class
        }) {
      assertThat(RuntimeHintsPredicates.reflection().onJavaSerialization(type, true))
          .as(type.getName())
          .accepts(hints);
    }
    for (String type : new String[] {"java.time.Ser", "java.util.CollSer"}) {
      assertThat(
              RuntimeHintsPredicates.reflection().onJavaSerialization(TypeReference.of(type), true))
          .as(type)
          .accepts(hints);
    }
  }

  @Test
  void the_authorization_request_kept_across_the_redirect_can_be_serialized() throws IOException {
    RuntimeHints hints = new RuntimeHints();
    new OidcRuntimeHints().registerHints(hints, getClass().getClassLoader());
    OAuth2AuthorizationRequest request =
        OAuth2AuthorizationRequest.authorizationCode()
            .authorizationUri("https://provider.example.com/authorize")
            .clientId("client")
            .redirectUri("https://app.example.com/login/oauth2/code/test")
            .scopes(Set.of("openid", "email"))
            .state("state")
            .additionalParameters(Map.of("nonce", "hashed-nonce", "prompt", "select_account"))
            .attributes(Map.of("registration_id", "test", "nonce", "nonce"))
            .build();

    for (Class<?> type : classesWritten(request)) {
      assertThat(RuntimeHintsPredicates.reflection().onJavaSerialization(type, true))
          .as(type.getName())
          .accepts(hints);
    }
  }

  /** Every class Java serialization writes for the object, as a session store would. */
  private static Set<Class<?>> classesWritten(Object value) throws IOException {
    Set<Class<?>> classes = new LinkedHashSet<>();
    try (ObjectOutputStream out =
        new ObjectOutputStream(new ByteArrayOutputStream()) {
          @Override
          protected void annotateClass(Class<?> type) {
            classes.add(type);
          }
        }) {
      out.writeObject(value);
    }
    return classes;
  }

  @Test
  void aot_factories_registers_the_hints() {
    assertThat(
            SpringFactoriesLoader.forResourceLocation("META-INF/spring/aot.factories")
                .load(RuntimeHintsRegistrar.class))
        .hasAtLeastOneElementOfType(OidcRuntimeHints.class);
  }
}
