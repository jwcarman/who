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
package org.jwcarman.who.core.id;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class IdentifiersTest {

  @Test
  void uuidReturnsNonNullValue() {
    assertThat(Identifiers.uuid()).isNotNull();
  }

  @Test
  void uuidReturnsUniqueValuesOnEachCall() {
    assertThat(Identifiers.uuid()).isNotEqualTo(Identifiers.uuid());
  }

  @Test
  void uuidIsVersion7() {
    assertThat(Identifiers.uuid().version()).isEqualTo(7);
  }

  @Test
  void createdAtIsReadFromTheTimeOrderedId() {
    Instant before = Instant.now().minusSeconds(1);
    UUID id = Identifiers.uuid();

    assertThat(Identifiers.createdAt(id)).isBetween(before, Instant.now().plusSeconds(1));
  }

  @Test
  void idsMadeBackToBackStrictlyIncrease() {
    UUID previous = Identifiers.uuid();
    for (int i = 0; i < 5000; i++) {
      UUID next = Identifiers.uuid();
      // unsigned, as a database orders a uuid; UUID.compareTo is signed
      assertThat(unsignedCompare(previous, next)).isNegative();
      previous = next;
    }
  }

  private static int unsignedCompare(UUID a, UUID b) {
    int high = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
    return high == 0
        ? Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits())
        : high;
  }
}
