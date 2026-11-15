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

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.UUID;

import org.jwcarman.who.oidc.PendingSignUp;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponseType;

/**
 * Lets a native image keep a {@link PendingSignUp}, and Spring's {@link OAuth2AuthorizationRequest}
 * that crosses the redirect to the provider, in a session store that serializes its attributes,
 * such as Spring Session JDBC. Spring Security registers no serialization hints for the request
 * itself.
 */
public class OidcRuntimeHints implements RuntimeHintsRegistrar {

  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    for (Class<?> type :
        new Class<?>[] {
          PendingSignUp.class,
          UUID.class,
          Instant.class,
          String.class,
          Boolean.class,
          Number.class,
          Long.class,
          Integer.class,
          Double.class,
          OAuth2AuthorizationRequest.class,
          AuthorizationGrantType.class,
          OAuth2AuthorizationResponseType.class,
          LinkedHashSet.class,
          HashSet.class,
          LinkedHashMap.class,
          HashMap.class
        }) {
      hints.reflection().registerJavaSerialization(type);
    }
    // not public: Instant serializes through java.time.Ser, and Map.copyOf's maps through
    // java.util.CollSer, by a writeReplace a native image calls only on a registered class; the
    // request's scopes and parameters are unmodifiable views
    for (String type :
        new String[] {
          "java.time.Ser",
          "java.util.CollSer",
          "java.util.ImmutableCollections$Map1",
          "java.util.ImmutableCollections$MapN",
          "java.util.ImmutableCollections$List12",
          "java.util.ImmutableCollections$ListN",
          "java.util.ImmutableCollections$Set12",
          "java.util.ImmutableCollections$SetN",
          "java.util.Collections$UnmodifiableCollection",
          "java.util.Collections$UnmodifiableSet",
          "java.util.Collections$UnmodifiableMap"
        }) {
      hints
          .reflection()
          .registerType(TypeReference.of(type), builder -> builder.withJavaSerialization(true));
    }
  }
}
