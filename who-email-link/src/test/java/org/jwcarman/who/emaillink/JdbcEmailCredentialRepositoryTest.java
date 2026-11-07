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
package org.jwcarman.who.emaillink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;

class JdbcEmailCredentialRepositoryTest extends AbstractEmailLinkTest {

  @Autowired private EmailCredentialRepository repository;

  @Test
  void findByIdReturnsTheSavedCredential() {
    EmailCredential saved = repository.save(EmailCredential.create("byid@example.com"));

    assertThat(repository.findById(saved.id())).contains(saved);
  }

  @Test
  void findByIdOfAnUnknownIdIsEmpty() {
    assertThat(repository.findById(UUID.randomUUID())).isEmpty();
  }

  @Test
  void deleteByIdReportsWhetherARowWasRemoved() {
    EmailCredential saved = repository.save(EmailCredential.create("del@example.com"));

    assertThat(repository.deleteById(saved.id())).isTrue();
    assertThat(repository.deleteById(saved.id())).isFalse();
    assertThat(repository.findById(saved.id())).isEmpty();
  }

  @Test
  void duplicateKeyIsStillTranslated() {
    repository.save(EmailCredential.create("dup@example.com"));

    assertThatThrownBy(() -> repository.save(EmailCredential.create("dup@example.com")))
        .isInstanceOf(DuplicateKeyException.class);
  }
}
