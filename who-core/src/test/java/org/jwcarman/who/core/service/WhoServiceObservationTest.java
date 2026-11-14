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
package org.jwcarman.who.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.IdentityStatus;
import org.jwcarman.who.core.event.WhoEventPublisher;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.core.spi.Credential;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;

/** {@link WhoService#resolveDetailed} runs inside a {@code who.principal.resolve} observation. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class WhoServiceObservationTest {

  private static final String NAME = "who.principal.resolve";
  private static final UUID CREDENTIAL_ID = UUID.randomUUID();
  private static final Credential CREDENTIAL = () -> CREDENTIAL_ID;

  private final IdentityRepository identities = mock(IdentityRepository.class);
  private final CredentialIdentityRepository links = mock(CredentialIdentityRepository.class);
  private final TestObservationRegistry registry = TestObservationRegistry.create();

  private WhoService service(ObservationRegistry observations) {
    return new WhoService(
        identities,
        links,
        identity -> Set.of("read"),
        List.of(),
        WhoEventPublisher.NOOP,
        Clock.systemUTC(),
        observations);
  }

  private void linkedTo(Identity identity) {
    when(links.findIdentityIdByCredentialId(CREDENTIAL_ID)).thenReturn(Optional.of(identity.id()));
    when(identities.findById(identity.id())).thenReturn(Optional.of(identity));
  }

  @Nested
  class Resolving {

    static Stream<Arguments> outcomes() {
      return Stream.of(
          Arguments.of("resolved", IdentityStatus.ACTIVE, true, true),
          Arguments.of("inactive", IdentityStatus.DISABLED, true, true),
          Arguments.of("no-identity", IdentityStatus.ACTIVE, true, false),
          Arguments.of("not-linked", IdentityStatus.ACTIVE, false, false));
    }

    @ParameterizedTest
    @MethodSource("outcomes")
    void each_resolution_is_one_observation_tagged_with_its_outcome(
        String outcome, IdentityStatus status, boolean linked, boolean identityExists) {
      Identity identity = Identity.create(status);
      if (linked) {
        when(links.findIdentityIdByCredentialId(CREDENTIAL_ID))
            .thenReturn(Optional.of(identity.id()));
      }
      if (identityExists) {
        when(identities.findById(identity.id())).thenReturn(Optional.of(identity));
      }

      service(registry).resolveDetailed(CREDENTIAL);

      TestObservationRegistryAssert.assertThat(registry)
          .hasNumberOfObservationsWithNameEqualTo(NAME, 1)
          .hasObservationWithNameEqualTo(NAME)
          .that()
          .hasContextualNameEqualTo("who resolve principal")
          .hasLowCardinalityKeyValue("outcome", outcome)
          .hasBeenStarted()
          .hasBeenStopped();
    }

    @Test
    void the_observation_carries_only_the_outcome() {
      linkedTo(Identity.create());
      List<Observation.Context> stopped = new ArrayList<>();
      ObservationRegistry recording = ObservationRegistry.create();
      recording
          .observationConfig()
          .observationHandler(
              new ObservationHandler<>() {
                @Override
                public boolean supportsContext(Observation.Context context) {
                  return true;
                }

                @Override
                public void onStop(Observation.Context context) {
                  stopped.add(context);
                }
              });

      service(recording).resolveDetailed(CREDENTIAL);

      assertThat(stopped).hasSize(1);
      Observation.Context context = stopped.getFirst();
      assertThat(context.getLowCardinalityKeyValues().stream().map(KeyValue::getKey))
          .containsExactly("outcome");
      assertThat(context.getHighCardinalityKeyValues()).isEmpty();
    }

    @Test
    void a_database_fault_propagates_and_is_recorded_on_the_observation() {
      IllegalStateException fault = new IllegalStateException("database down");
      when(links.findIdentityIdByCredentialId(CREDENTIAL_ID)).thenThrow(fault);

      assertThatThrownBy(() -> service(registry).resolveDetailed(CREDENTIAL)).isSameAs(fault);

      TestObservationRegistryAssert.assertThat(registry)
          .hasObservationWithNameEqualTo(NAME)
          .that()
          .hasError(fault)
          .hasLowCardinalityKeyValue("outcome", "error")
          .hasBeenStopped();
    }
  }

  @Nested
  class Failing_observation_handlers {

    private ObservationRegistry throwing;

    @BeforeEach
    void setUp() {
      throwing = ObservationRegistry.create();
      throwing
          .observationConfig()
          .observationHandler(
              new ObservationHandler<>() {
                @Override
                public boolean supportsContext(Observation.Context context) {
                  return true;
                }

                @Override
                public void onStart(Observation.Context context) {
                  throw new IllegalStateException("handler start");
                }

                @Override
                public void onStop(Observation.Context context) {
                  throw new IllegalStateException("handler stop");
                }
              });
    }

    @Test
    void do_not_change_the_resolution() {
      Identity identity = Identity.create();
      linkedTo(identity);

      Resolution resolution = service(throwing).resolveDetailed(CREDENTIAL);

      assertThat(resolution).isInstanceOf(Resolution.Resolved.class);
      assertThat(resolution.principal())
          .hasValueSatisfying(p -> assertThat(p.identity()).isEqualTo(identity));
    }
  }

  @Nested
  class Without_a_registry {

    @Test
    void the_existing_constructors_resolve_unobserved() {
      Identity identity = Identity.create();
      linkedTo(identity);
      WhoService unobserved =
          new WhoService(
              identities,
              links,
              i -> Set.of(),
              List.of(),
              WhoEventPublisher.NOOP,
              Clock.systemUTC());

      assertThat(unobserved.resolveDetailed(CREDENTIAL)).isInstanceOf(Resolution.Resolved.class);
    }
  }
}
