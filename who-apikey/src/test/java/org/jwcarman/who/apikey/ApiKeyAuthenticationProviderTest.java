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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.event.SignInFailed;
import org.jwcarman.who.core.event.SignInFailureReason;
import org.jwcarman.who.core.event.WhoEvent;
import org.jwcarman.who.core.service.Resolution;
import org.jwcarman.who.core.service.WhoService;
import org.jwcarman.who.spring.security.WhoAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ApiKeyAuthenticationProviderTest {

  private static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");
  private static final Duration INTERVAL = Duration.ofMinutes(5);
  private static final ApiKeyFormat FORMAT = new ApiKeyFormat(ApiKeyFormat.DEFAULT_PREFIX);

  private final ApiKeyCredentialRepository repository = mock(ApiKeyCredentialRepository.class);
  private final WhoService whoService = mock(WhoService.class);
  private final List<WhoEvent> events = new ArrayList<>();
  private final ApiKeyAuthenticationProvider provider =
      new ApiKeyAuthenticationProvider(
          repository, whoService, FORMAT, INTERVAL, events::add, Clock.fixed(NOW, ZoneOffset.UTC));
  private final Identity owner = Identity.create();
  private final String key = FORMAT.generate();

  /** A stored key for {@link #key}, never used, whose owner holds {@code held}. */
  private ApiKeyCredential stored(Set<String> scopes, Instant expiresAt, Set<String> held) {
    return stored(scopes, expiresAt, held, null);
  }

  private ApiKeyCredential stored(
      Set<String> scopes, Instant expiresAt, Set<String> held, Instant lastUsedAt) {
    ApiKeyCredential credential =
        new ApiKeyCredential(
            UUID.randomUUID(),
            "k",
            ApiKeyService.sha256Hex(key),
            scopes,
            NOW.minus(Duration.ofDays(1)),
            expiresAt,
            lastUsedAt);
    when(repository.findByKeyHash(credential.keyHash())).thenReturn(Optional.of(credential));
    when(whoService.resolveDetailed(credential))
        .thenReturn(new Resolution.Resolved(new WhoPrincipal(owner, held)));
    return credential;
  }

  private WhoPrincipal signIn() {
    Authentication result = provider.authenticate(new ApiKeyAuthenticationToken(key));
    return ((WhoAuthenticationToken) result).getPrincipal();
  }

  private SignInFailed refused(String presented) {
    ApiKeyAuthenticationToken token = new ApiKeyAuthenticationToken(presented);
    assertThatThrownBy(() -> provider.authenticate(token))
        .isInstanceOf(BadCredentialsException.class);
    assertThat(events).singleElement().isInstanceOf(SignInFailed.class);
    return (SignInFailed) events.getFirst();
  }

  @Test
  void an_expired_key_fails_expired_key() {
    ApiKeyCredential credential = stored(Set.of("task.read"), NOW, Set.of("task.read"));

    SignInFailed failed = refused(key);

    assertThat(failed.reason()).isEqualTo(SignInFailureReason.EXPIRED_KEY);
    assertThat(failed.credentialId()).isEqualTo(credential.id());
    assertThat(failed.identityId()).isEqualTo(owner.id());
  }

  @Test
  void a_key_signs_in_with_only_its_scopes() {
    stored(Set.of("task.read"), NOW.plusSeconds(60), Set.of("task.read", "task.write"));

    WhoPrincipal principal = signIn();

    assertThat(principal.identity()).isEqualTo(owner);
    assertThat(principal.permissions()).containsExactly("task.read");
  }

  @Test
  void a_demoted_owners_key_loses_the_permission_at_once() {
    stored(Set.of("task.read", "task.write"), NOW.plusSeconds(60), Set.of("task.read"));

    assertThat(signIn().permissions()).containsExactly("task.read");
  }

  @Test
  void an_owner_without_any_scoped_permission_signs_in_with_none() {
    stored(Set.of("task.write"), NOW.plusSeconds(60), Set.of("task.read"));

    assertThat(signIn().permissions()).isEmpty();
  }

  @Test
  void a_bad_checksum_fails_unknown_key_without_a_lookup() {
    String forged = key.substring(0, key.length() - 1) + (key.endsWith("a") ? "b" : "a");

    assertThat(refused(forged).reason()).isEqualTo(SignInFailureReason.UNKNOWN_KEY);
    verifyNoInteractions(repository, whoService);
  }

  @Test
  void a_valid_looking_unknown_key_fails_unknown_key() {
    when(repository.findByKeyHash(any())).thenReturn(Optional.empty());

    assertThat(refused(FORMAT.generate()).reason()).isEqualTo(SignInFailureReason.UNKNOWN_KEY);
  }

  @Test
  void a_sign_in_records_its_use_unless_one_was_recorded_within_the_interval() {
    ApiKeyCredential credential =
        stored(Set.of("task.read"), NOW.plusSeconds(60), Set.of("task.read"));

    signIn();

    verify(repository).touch(credential.id(), NOW, NOW.minus(INTERVAL));
  }

  @Test
  void a_key_used_within_the_interval_issues_no_write() {
    stored(
        Set.of("task.read"),
        NOW.plusSeconds(60),
        Set.of("task.read"),
        NOW.minus(INTERVAL).plusSeconds(1));

    signIn();

    verify(repository, never()).touch(any(), any(), any());
  }

  @Test
  void a_key_last_used_longer_ago_than_the_interval_records_its_use() {
    ApiKeyCredential credential =
        stored(
            Set.of("task.read"),
            NOW.plusSeconds(60),
            Set.of("task.read"),
            NOW.minus(INTERVAL).minusSeconds(1));

    signIn();

    verify(repository).touch(credential.id(), NOW, NOW.minus(INTERVAL));
  }

  @Test
  void a_failed_last_used_write_still_signs_in() {
    ApiKeyCredential credential =
        stored(Set.of("task.read"), NOW.plusSeconds(60), Set.of("task.read"));
    when(repository.touch(any(), any(), any())).thenThrow(new IllegalStateException("down"));

    assertThat(signIn().permissions()).containsExactly("task.read");
    verify(repository).touch(credential.id(), NOW, NOW.minus(INTERVAL));
  }

  @Test
  void a_non_positive_interval_is_refused() {
    Clock clock = Clock.systemUTC();
    assertThatThrownBy(
            () ->
                new ApiKeyAuthenticationProvider(
                    repository, whoService, FORMAT, Duration.ZERO, events::add, clock))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
