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
package org.jwcarman.who.core.spi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class CredentialRemoverTest {

  /** A remover that implements only what the interface requires. */
  private static final CredentialRemover MINIMAL =
      new CredentialRemover() {
        @Override
        public boolean remove(UUID credentialId) {
          return false;
        }

        @Override
        public int removeUnlinked() {
          return 0;
        }
      };

  @Nested
  class Defaults {

    @Test
    void a_remover_that_cannot_describe_its_credentials_finds_none() {
      assertThat(MINIMAL.find(UUID.randomUUID())).isEmpty();
    }
  }
}
