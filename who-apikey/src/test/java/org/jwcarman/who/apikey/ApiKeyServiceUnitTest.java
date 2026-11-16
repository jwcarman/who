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
package org.jwcarman.who.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.event.CredentialAdded;
import org.jwcarman.who.core.event.CredentialKind;
import org.jwcarman.who.core.event.WhoEvent;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ApiKeyServiceUnitTest {

  private static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");
  private static final Duration MAX = Duration.ofDays(365);

  private final ApiKeyCredentialRepository keys = mock(ApiKeyCredentialRepository.class);
  private final CredentialIdentityRepository links = mock(CredentialIdentityRepository.class);
  private final List<WhoEvent> events = new ArrayList<>();
  private final Identity owner = Identity.create();
  private final ApiKeyService service =
      new ApiKeyService(
          keys,
          links,
          identity -> Set.of("task.read", "task.write"),
          new ApiKeyFormat(ApiKeyFormat.DEFAULT_PREFIX),
          MAX,
          events::add,
          Clock.fixed(NOW, ZoneOffset.UTC));

  static Stream<Arguments> refused() {
    Set<String> withBlank = new HashSet<>(Set.of("task.read"));
    withBlank.add(" ");
    return Stream.of(
        Arguments.of("a blank name", " ", Duration.ofDays(1), Set.of("task.read"), "name"),
        Arguments.of("a zero lifetime", "k", Duration.ZERO, Set.of("task.read"), "lifetime"),
        Arguments.of(
            "a negative lifetime", "k", Duration.ofDays(-1), Set.of("task.read"), "lifetime"),
        Arguments.of(
            "a lifetime over the maximum",
            "k",
            MAX.plusSeconds(1),
            Set.of("task.read"),
            "lifetime"),
        Arguments.of("no scopes", "k", Duration.ofDays(1), Set.of(), "scope"),
        Arguments.of("a blank scope", "k", Duration.ofDays(1), withBlank, "scope"),
        Arguments.of(
            "a scope the owner lacks",
            "k",
            Duration.ofDays(1),
            Set.of("task.read", "task.delete"),
            "task.delete"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("refused")
  void create_refuses(
      String what, String name, Duration lifetime, Set<String> scopes, String says) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> service.create(owner, name, lifetime, scopes))
        .withMessageContaining(says);
    verify(keys, never()).save(any());
    verify(links, never()).link(any(), any());
    assertThat(events).isEmpty();
  }

  @Test
  void create_refuses_nulls() {
    Duration day = Duration.ofDays(1);
    Set<String> read = Set.of("task.read");
    assertThatNullPointerException().isThrownBy(() -> service.create(null, "k", day, read));
    assertThatNullPointerException().isThrownBy(() -> service.create(owner, null, day, read));
    assertThatNullPointerException().isThrownBy(() -> service.create(owner, "k", null, read));
    assertThatNullPointerException().isThrownBy(() -> service.create(owner, "k", day, null));
  }

  @Test
  void create_publishes_credential_added_labelled_with_the_name() {
    NewApiKey key = service.create(owner, "CI", Duration.ofDays(1), Set.of("task.read"));

    assertThat(events)
        .singleElement()
        .isInstanceOfSatisfying(
            CredentialAdded.class,
            e -> {
              assertThat(e.identityId()).isEqualTo(owner.id());
              assertThat(e.credentialId()).isEqualTo(key.id());
              assertThat(e.kind()).isEqualTo(CredentialKind.API_KEY);
              assertThat(e.label()).isEqualTo("CI");
            });
    assertThat(events.getFirst().toString()).doesNotContain(key.key());
  }

  @Test
  void rotate_publishes_credential_added_for_the_new_key_only() {
    UUID oldId = UUID.randomUUID();
    when(keys.findById(oldId))
        .thenReturn(
            Optional.of(
                new ApiKeyCredential(
                    oldId,
                    "CI",
                    "hash",
                    Set.of("task.read"),
                    NOW,
                    NOW.plus(Duration.ofDays(9)),
                    null)));
    when(links.findIdentityIdByCredentialId(oldId)).thenReturn(Optional.of(owner.id()));
    when(keys.expireBy(any(), any(), any())).thenReturn(true);

    NewApiKey rotated = service.rotate(oldId, Duration.ofDays(1));

    assertThat(events)
        .singleElement()
        .isInstanceOfSatisfying(
            CredentialAdded.class, e -> assertThat(e.credentialId()).isEqualTo(rotated.id()));
  }

  @Test
  void a_rotated_key_lives_at_most_the_maximum() {
    UUID oldId = UUID.randomUUID();
    // made when the maximum was longer
    when(keys.findById(oldId))
        .thenReturn(
            Optional.of(
                new ApiKeyCredential(
                    oldId,
                    "CI",
                    "hash",
                    Set.of("task.read"),
                    NOW,
                    NOW.plus(Duration.ofDays(900)),
                    null)));
    when(links.findIdentityIdByCredentialId(oldId)).thenReturn(Optional.of(owner.id()));
    when(keys.expireBy(any(), any(), any())).thenReturn(true);

    assertThat(service.rotate(oldId, Duration.ofDays(1)).expiresAt()).isEqualTo(NOW.plus(MAX));
  }

  @Test
  void a_key_revoked_or_expired_during_rotation_gets_no_replacement() {
    UUID oldId = UUID.randomUUID();
    when(keys.findById(oldId))
        .thenReturn(
            Optional.of(
                new ApiKeyCredential(
                    oldId,
                    "CI",
                    "hash",
                    Set.of("task.read"),
                    NOW,
                    NOW.plus(Duration.ofDays(9)),
                    null)));
    when(links.findIdentityIdByCredentialId(oldId)).thenReturn(Optional.of(owner.id()));
    // the row went, or expired, after it was read
    when(keys.expireBy(oldId, NOW.plus(Duration.ofDays(1)), NOW)).thenReturn(false);
    Duration overlap = Duration.ofDays(1);

    assertThatIllegalArgumentException().isThrownBy(() -> service.rotate(oldId, overlap));
    verify(keys, never()).save(any());
    assertThat(events).isEmpty();
  }

  @Test
  void rotate_refuses_a_negative_overlap() {
    UUID id = UUID.randomUUID();
    assertThatIllegalArgumentException()
        .isThrownBy(() -> service.rotate(id, Duration.ofSeconds(-1)))
        .withMessageContaining("overlap");
  }

  @Test
  void new_api_key_to_string_leaves_out_the_key() {
    NewApiKey key = service.create(owner, "CI", Duration.ofDays(1), Set.of("task.read"));

    assertThat(key.toString()).doesNotContain(key.key()).contains("CI");
  }

  @Test
  void a_non_positive_maximum_is_refused() {
    ApiKeyFormat format = new ApiKeyFormat(ApiKeyFormat.DEFAULT_PREFIX);
    Clock clock = Clock.systemUTC();
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new ApiKeyService(
                    keys, links, identity -> Set.of(), format, Duration.ZERO, events::add, clock));
  }
}
