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
package org.jwcarman.who.passkey.aot;

import java.io.IOException;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.util.ClassUtils;

/**
 * Native-image reflection for passkeys: Spring Security WebAuthn's JSON model and webauthn4j's CBOR
 * model. Which key type and attestation format arrive depends on the user's authenticator, so whole
 * packages ("families") are registered rather than only the types a trace happened to see.
 */
public class PasskeyRuntimeHints implements RuntimeHintsRegistrar {

  /** Packages whose every class (nested classes included, subpackages not) is registered. */
  public static final List<String> FAMILIES =
      List.of(
          "org/springframework/security/web/webauthn/api/",
          "org/springframework/security/web/webauthn/jackson/",
          "com/webauthn4j/data/attestation/",
          "com/webauthn4j/data/attestation/authenticator/",
          "com/webauthn4j/data/attestation/statement/",
          "com/webauthn4j/data/client/");

  /** Spring Security WebAuthn types outside the families that the native trace needed. */
  static final List<String> NAMED_TYPES =
      List.of(
          "org.springframework.security.web.webauthn.authentication.WebAuthnAuthenticationFilter",
          "org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository",
          "org.springframework.security.web.webauthn.management.RelyingPartyPublicKey",
          "org.springframework.security.web.webauthn.management.UserCredentialRepository",
          "org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations",
          "org.springframework.security.web.webauthn.registration.WebAuthnRegistrationFilter$SuccessfulUserRegistrationResponse",
          "org.springframework.security.web.webauthn.registration.WebAuthnRegistrationFilter$WebAuthnRegistrationRequest");

  /** Array types Jackson needs, from the native trace. */
  static final List<String> ARRAY_TYPES =
      List.of("org.springframework.security.web.webauthn.api.PublicKeyCredentialParameters[]");

  /**
   * Public and declared: GraalVM treats declared as a superset, but Spring's hint predicates check
   * public members against the public categories, and public methods also reach inherited getters.
   */
  private static final MemberCategory[] MEMBERS = {
    MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
    MemberCategory.INVOKE_PUBLIC_METHODS,
    MemberCategory.INVOKE_DECLARED_METHODS,
    MemberCategory.ACCESS_PUBLIC_FIELDS,
    MemberCategory.ACCESS_DECLARED_FIELDS
  };

  private static final String SERIALIZABLE_FAMILY =
      "org/springframework/security/web/webauthn/api/";

  /** JDK types held by the ceremony options. */
  private static final List<TypeReference> JDK_SERIALIZABLE =
      List.of(
          TypeReference.of(Duration.class),
          TypeReference.of(Boolean.class),
          TypeReference.of(HashSet.class),
          TypeReference.of("java.util.Arrays$ArrayList"),
          TypeReference.of(byte[].class));

  private static final String CLASS_SUFFIX = ".class";

  @Override
  public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
    ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(classLoader);
    for (String family : FAMILIES) {
      for (String name : classesIn(resolver, family)) {
        register(hints, name);
      }
    }
    registerSerialization(hints, resolver, classLoader);
    NAMED_TYPES.forEach(name -> register(hints, name));
    ARRAY_TYPES.forEach(name -> hints.reflection().registerType(TypeReference.of(name)));
  }

  /**
   * Ceremony options are Java-serialized into the session by Spring Session, so each {@link
   * Serializable} class in the API family needs a serialization hint, plus the JDK types the
   * options hold.
   */
  private static void registerSerialization(
      RuntimeHints hints, ResourcePatternResolver resolver, ClassLoader classLoader) {
    for (String name : classesIn(resolver, SERIALIZABLE_FAMILY)) {
      if (isSerializable(name, classLoader)) {
        hints.serialization().registerType(TypeReference.of(name));
      }
    }
    JDK_SERIALIZABLE.forEach(type -> hints.serialization().registerType(type));
  }

  private static boolean isSerializable(String className, ClassLoader classLoader) {
    try {
      return Serializable.class.isAssignableFrom(ClassUtils.forName(className, classLoader));
    } catch (ClassNotFoundException | LinkageError _) {
      return false;
    }
  }

  private static void register(RuntimeHints hints, String className) {
    hints.reflection().registerType(TypeReference.of(className), MEMBERS);
  }

  static List<String> classesIn(ResourcePatternResolver resolver, String family) {
    try {
      Resource[] resources = resolver.getResources("classpath*:" + family + "*" + CLASS_SUFFIX);
      return Arrays.stream(resources)
          .map(Resource::getFilename)
          .filter(file -> file != null && !file.equals("package-info" + CLASS_SUFFIX))
          .map(
              file ->
                  (family + file.substring(0, file.length() - CLASS_SUFFIX.length()))
                      .replace('/', '.'))
          .distinct()
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
