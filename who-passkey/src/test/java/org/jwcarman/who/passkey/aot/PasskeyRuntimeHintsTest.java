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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.io.support.SpringFactoriesLoader;

class PasskeyRuntimeHintsTest {

  private static final MemberCategory[] MEMBERS = {
    MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
    MemberCategory.INVOKE_PUBLIC_METHODS,
    MemberCategory.INVOKE_DECLARED_METHODS,
    MemberCategory.ACCESS_PUBLIC_FIELDS,
    MemberCategory.ACCESS_DECLARED_FIELDS
  };

  /** The types the native trace named (native-findings G4a and G4b), plus RSA and packed. */
  private static final List<String> NAMED =
      List.of(
          "com.webauthn4j.data.attestation.AttestationObject",
          "com.webauthn4j.data.attestation.authenticator.EC2COSEKey",
          "com.webauthn4j.data.attestation.authenticator.EdDSACOSEKey",
          "com.webauthn4j.data.attestation.authenticator.RSACOSEKey",
          "com.webauthn4j.data.attestation.statement.NoneAttestationStatement",
          "com.webauthn4j.data.attestation.statement.PackedAttestationStatement",
          "com.webauthn4j.data.client.CollectedClientData",
          "com.webauthn4j.data.client.ClientDataType",
          "org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions",
          "org.springframework.security.web.webauthn.api.Bytes",
          "org.springframework.security.web.webauthn.jackson.PublicKeyCredentialCreationOptionsMixin",
          // The seven outside the scanned packages.
          "org.springframework.security.web.webauthn.authentication.WebAuthnAuthenticationFilter",
          "org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository",
          "org.springframework.security.web.webauthn.management.RelyingPartyPublicKey",
          "org.springframework.security.web.webauthn.management.UserCredentialRepository",
          "org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations",
          "org.springframework.security.web.webauthn.registration.WebAuthnRegistrationFilter$SuccessfulUserRegistrationResponse",
          "org.springframework.security.web.webauthn.registration.WebAuthnRegistrationFilter$WebAuthnRegistrationRequest");

  private final ClassLoader classLoader = getClass().getClassLoader();

  private RuntimeHints hints() {
    RuntimeHints hints = new RuntimeHints();
    new PasskeyRuntimeHints().registerHints(hints, classLoader);
    return hints;
  }

  @Test
  void familiesAreTheSixPackages() {
    assertThat(PasskeyRuntimeHints.FAMILIES)
        .containsExactly(
            "org/springframework/security/web/webauthn/api/",
            "org/springframework/security/web/webauthn/jackson/",
            "com/webauthn4j/data/attestation/",
            "com/webauthn4j/data/attestation/authenticator/",
            "com/webauthn4j/data/attestation/statement/",
            "com/webauthn4j/data/client/");
  }

  @Test
  void everyClassInTheFamiliesIsRegistered() throws IOException {
    RuntimeHints hints = hints();
    PathMatchingResourcePatternResolver resolver =
        new PathMatchingResourcePatternResolver(classLoader);
    for (String family : PasskeyRuntimeHints.FAMILIES) {
      List<String> names = new ArrayList<>();
      for (Resource resource : resolver.getResources("classpath*:" + family + "*.class")) {
        String file = resource.getFilename();
        if (file != null && !file.equals("package-info.class")) {
          names.add(
              (family + file.substring(0, file.length() - ".class".length())).replace('/', '.'));
        }
      }
      assertThat(names).as("classes in %s", family).isNotEmpty();
      for (String name : names) {
        assertThat(
                RuntimeHintsPredicates.reflection()
                    .onType(TypeReference.of(name))
                    .withMemberCategories(MEMBERS))
            .as(name)
            .accepts(hints);
      }
    }
  }

  @Test
  void nestedClassesAreIncluded() {
    assertThat(
            RuntimeHintsPredicates.reflection()
                .onType(
                    TypeReference.of(
                        "org.springframework.security.web.webauthn.api.PublicKeyCredential$PublicKeyCredentialBuilder"))
                .withMemberCategories(MEMBERS))
        .accepts(hints());
  }

  @Test
  void namedTypesFromTheFindingsAreRegistered() throws ClassNotFoundException {
    RuntimeHints hints = hints();
    for (String name : NAMED) {
      assertThat(
              RuntimeHintsPredicates.reflection()
                  .onType(TypeReference.of(name))
                  .withMemberCategories(MEMBERS))
          .as(name)
          .accepts(hints);
      Class<?> type = Class.forName(name, false, classLoader);
      for (Constructor<?> constructor : type.getDeclaredConstructors()) {
        assertThat(RuntimeHintsPredicates.reflection().onConstructorInvocation(constructor))
            .as(constructor.toString())
            .accepts(hints);
      }
      for (Method method : type.getDeclaredMethods()) {
        assertThat(RuntimeHintsPredicates.reflection().onMethodInvocation(method))
            .as(method.toString())
            .accepts(hints);
      }
      for (Field field : type.getDeclaredFields()) {
        assertThat(RuntimeHintsPredicates.reflection().onFieldAccess(field))
            .as(field.toString())
            .accepts(hints);
      }
    }
  }

  @Test
  void parametersArrayFromTheFindingsIsRegistered() {
    assertThat(
            RuntimeHintsPredicates.reflection()
                .onType(
                    TypeReference.of(
                        "org.springframework.security.web.webauthn.api.PublicKeyCredentialParameters[]")))
        .accepts(hints());
  }

  private static final String API = "org.springframework.security.web.webauthn.api.";

  @Test
  void creationAndRequestOptionsAreSerializable() {
    RuntimeHints hints = hints();
    for (String name :
        List.of(
            "PublicKeyCredentialCreationOptions",
            "PublicKeyCredentialRequestOptions",
            "ImmutablePublicKeyCredentialUserEntity",
            "Bytes",
            "ImmutableAuthenticationExtensionsClientInputs")) {
      assertThat(RuntimeHintsPredicates.serialization().onType(TypeReference.of(API + name)))
          .as(name)
          .accepts(hints);
    }
  }

  @Test
  void everySerializableWebAuthnApiTypeIsSerializable() throws ClassNotFoundException {
    RuntimeHints hints = hints();
    ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(classLoader);
    int serializable = 0;
    for (String name :
        PasskeyRuntimeHints.classesIn(resolver, "org/springframework/security/web/webauthn/api/")) {
      if (Serializable.class.isAssignableFrom(Class.forName(name, false, classLoader))) {
        serializable++;
        assertThat(RuntimeHintsPredicates.serialization().onType(TypeReference.of(name)))
            .as(name)
            .accepts(hints);
      }
    }
    assertThat(serializable).isPositive();
  }

  @Test
  void jdkTypesForOptionsAreSerializable() {
    RuntimeHints hints = hints();
    for (Class<?> type :
        new Class<?>[] {Duration.class, Boolean.class, HashSet.class, byte[].class}) {
      assertThat(RuntimeHintsPredicates.serialization().onType(type))
          .as(type.getName())
          .accepts(hints);
    }
    for (String name : List.of("java.util.Arrays$ArrayList", "java.time.Ser")) {
      assertThat(RuntimeHintsPredicates.serialization().onType(TypeReference.of(name)))
          .as(name)
          .accepts(hints);
    }
  }

  @Test
  void nonSerializableTypesGetNoSerializationHint() throws ClassNotFoundException {
    RuntimeHints hints = hints();
    ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(classLoader);
    List<String> notSerializable = new ArrayList<>();
    for (String name :
        PasskeyRuntimeHints.classesIn(resolver, PasskeyRuntimeHints.FAMILIES.get(0))) {
      if (!Serializable.class.isAssignableFrom(Class.forName(name, false, classLoader))) {
        notSerializable.add(name);
      }
    }
    assertThat(notSerializable).isNotEmpty();
    for (String name : notSerializable) {
      assertThat(RuntimeHintsPredicates.serialization().onType(TypeReference.of(name)))
          .as(name)
          .rejects(hints);
    }
  }

  @Test
  void aTypeThatFailsToLoadIsSkipped() {
    ClassLoader failing =
        new ClassLoader(classLoader) {
          @Override
          protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.endsWith(".Bytes")) {
              throw new NoClassDefFoundError(name);
            }
            return super.loadClass(name, resolve);
          }
        };
    RuntimeHints hints = new RuntimeHints();
    new PasskeyRuntimeHints().registerHints(hints, failing);

    assertThat(RuntimeHintsPredicates.serialization().onType(TypeReference.of(API + "Bytes")))
        .rejects(hints);
  }

  @Test
  void registrarIsListedInAotFactories() {
    assertThat(
            SpringFactoriesLoader.forResourceLocation("META-INF/spring/aot.factories")
                .load(RuntimeHintsRegistrar.class))
        .hasAtLeastOneElementOfType(PasskeyRuntimeHints.class);
  }

  @Test
  void scanSkipsPackageInfoAndResourcesWithoutAFileName() throws IOException {
    ResourcePatternResolver resolver = mock(ResourcePatternResolver.class);
    when(resolver.getResources("classpath*:com/example/*.class"))
        .thenReturn(
            new Resource[] {
              new ClassPathResource("com/example/Foo.class"),
              new ClassPathResource("com/example/package-info.class"),
              new ByteArrayResource(new byte[0]),
              new ClassPathResource("com/example/Foo.class")
            });

    assertThat(PasskeyRuntimeHints.classesIn(resolver, "com/example/"))
        .containsExactly("com.example.Foo");
  }

  @Test
  void aFailedScanIsRethrownUnchecked() throws IOException {
    ResourcePatternResolver resolver = mock(ResourcePatternResolver.class);
    when(resolver.getResources("classpath*:com/example/*.class"))
        .thenThrow(new IOException("unreadable jar"));

    assertThatThrownBy(() -> PasskeyRuntimeHints.classesIn(resolver, "com/example/"))
        .isInstanceOf(UncheckedIOException.class)
        .hasRootCauseMessage("unreadable jar");
  }
}
