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
package org.jwcarman.who.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class LastAdminGuardTest {

  /** Stops the transaction before any SQL: only the definition it was asked for matters here. */
  private static final class Asked extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }

  @Nested
  class Isolation {

    @Test
    void counts_and_changes_at_read_committed_whatever_the_database_default() {
      PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
      List<TransactionDefinition> asked = new ArrayList<>();
      when(manager.getTransaction(any()))
          .thenAnswer(
              call -> {
                asked.add(call.getArgument(0));
                throw new Asked();
              });
      LastAdminGuard guard = new LastAdminGuard(mock(JdbcClient.class), manager, true);
      UUID identityId = UUID.randomUUID();

      assertThatThrownBy(() -> guard.deactivate(identityId, () -> true)).isInstanceOf(Asked.class);

      assertThat(asked)
          .singleElement()
          .extracting(TransactionDefinition::getIsolationLevel)
          .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
  }
}
