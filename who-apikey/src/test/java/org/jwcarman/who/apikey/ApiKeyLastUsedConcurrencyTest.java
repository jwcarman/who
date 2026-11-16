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

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Two requests with the same key at once: committed rows, so no test transaction. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ApiKeyLastUsedConcurrencyTest extends AbstractApiKeyTest {

  @Autowired private ApiKeyCredentialRepository repository;

  private UUID id;

  @AfterEach
  void removeKey() {
    if (id != null) {
      repository.deleteById(id);
    }
  }

  @Test
  void concurrent_sign_ins_write_last_used_once() throws Exception {
    Instant created = Instant.now().truncatedTo(ChronoUnit.MICROS);
    id = UUID.randomUUID();
    repository.save(
        new ApiKeyCredential(
            id,
            "busy",
            "hash-" + id,
            Set.of("task.read"),
            created,
            created.plus(Duration.ofDays(1)),
            null));
    Instant now = created.plusSeconds(1);
    CyclicBarrier together = new CyclicBarrier(2);

    List<Boolean> wrote;
    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
      Future<Boolean> one = pool.submit(() -> touchTogether(together, now));
      Future<Boolean> two = pool.submit(() -> touchTogether(together, now));
      wrote = List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS));
    }

    assertThat(wrote).containsExactlyInAnyOrder(true, false);
    assertThat(repository.findById(id))
        .hasValueSatisfying(found -> assertThat(found.lastUsedAt()).isEqualTo(now));
  }

  private boolean touchTogether(CyclicBarrier together, Instant now) throws Exception {
    together.await(5, TimeUnit.SECONDS);
    return repository.touch(id, now, now.minus(Duration.ofMinutes(5)));
  }
}
