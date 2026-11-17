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
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.exception.LastCredentialException;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class LastCredentialGuardTest {

  private final UUID identityId = UUID.randomUUID();

  private static SignInCredentialCounter counter(String kind, long count) {
    return new SignInCredentialCounter() {
      @Override
      public String kind() {
        return kind;
      }

      @Override
      public long count(UUID identityId) {
        return count;
      }
    };
  }

  private static LastCredentialGuard guard(long email, long passkey, long account) {
    PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    return new LastCredentialGuard(
        mock(IdentityRepository.class),
        List.of(counter("email", email), counter("passkey", passkey), counter("account", account)),
        manager);
  }

  @Nested
  class Removing {

    @Test
    void throws_when_one_credential_remains() {
      AtomicBoolean ran = new AtomicBoolean();

      assertThatThrownBy(
              () ->
                  guard(1, 0, 0)
                      .removeIfNotLast(
                          identityId,
                          () -> {
                            ran.set(true);
                            return "removed";
                          }))
          .isInstanceOf(LastCredentialException.class);

      assertThat(ran).isFalse();
    }

    @Test
    void runs_the_removal_when_two_remain() {
      String result = guard(1, 1, 0).removeIfNotLast(identityId, () -> "removed");

      assertThat(result).isEqualTo("removed");
    }
  }

  @Nested
  class Counting {

    @Test
    void counts_by_kind() {
      assertThat(guard(2, 1, 0).counts(identityId))
          .containsExactly(entry("email", 2L), entry("passkey", 1L), entry("account", 0L));
    }
  }

  @Nested
  class Isolation {

    @Test
    void counts_and_removes_at_read_committed_whatever_the_database_default() {
      PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
      List<TransactionDefinition> asked = new ArrayList<>();
      when(manager.getTransaction(any()))
          .thenAnswer(
              call -> {
                asked.add(call.getArgument(0));
                return new SimpleTransactionStatus();
              });
      LastCredentialGuard guard =
          new LastCredentialGuard(
              mock(IdentityRepository.class), List.of(counter("email", 2)), manager);

      guard.removeIfNotLast(identityId, () -> true);

      assertThat(asked)
          .singleElement()
          .extracting(TransactionDefinition::getIsolationLevel)
          .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
  }
}
