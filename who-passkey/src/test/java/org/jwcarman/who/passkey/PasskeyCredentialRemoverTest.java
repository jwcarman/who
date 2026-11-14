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
package org.jwcarman.who.passkey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Passkey rows go with their identity's, so who's identity cleanup never deletes them itself. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class PasskeyCredentialRemoverTest {

  private final JdbcWhoPasskeyCredentialRepository repository =
      mock(JdbcWhoPasskeyCredentialRepository.class);
  private final PasskeyCredentialRemover remover = new PasskeyCredentialRemover(repository);

  @Nested
  class Removing {

    @Test
    void removes_nothing_by_id() {
      assertThat(remover.remove(UUID.randomUUID())).isFalse();
      verifyNoInteractions(repository);
    }

    @Test
    void finds_nothing_unlinked_to_remove() {
      assertThat(remover.removeUnlinked()).isZero();
      verifyNoInteractions(repository);
    }
  }
}
