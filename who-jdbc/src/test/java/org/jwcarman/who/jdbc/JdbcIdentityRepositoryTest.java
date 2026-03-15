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
package org.jwcarman.who.jdbc;

import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.IdentityStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcIdentityRepositoryTest extends AbstractJdbcTest {

    @Autowired
    private JdbcIdentityRepository repository;

    @Test
    void savesNewIdentityAndRetrievesItById() {
        Identity identity = Identity.create(UUID.randomUUID(), IdentityStatus.ACTIVE);
        repository.save(identity);

        Optional<Identity> found = repository.findById(identity.id());

        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(identity.id());
        assertThat(found.get().status()).isEqualTo(IdentityStatus.ACTIVE);
    }

    @Test
    void upsertUpdatesStatusOnConflict() {
        Identity identity = Identity.create(UUID.randomUUID(), IdentityStatus.ACTIVE);
        repository.save(identity);

        Identity suspended = identity.withStatus(IdentityStatus.SUSPENDED);
        repository.save(suspended);

        Optional<Identity> found = repository.findById(identity.id());
        assertThat(found).isPresent();
        assertThat(found.get().status()).isEqualTo(IdentityStatus.SUSPENDED);
    }

    @Test
    void existsByIdReturnsTrueWhenIdentityExists() {
        Identity identity = Identity.create(UUID.randomUUID(), IdentityStatus.ACTIVE);
        repository.save(identity);

        assertThat(repository.existsById(identity.id())).isTrue();
    }

    @Test
    void existsByIdReturnsFalseWhenIdentityAbsent() {
        assertThat(repository.existsById(UUID.randomUUID())).isFalse();
    }

    @Test
    void deleteByIdRemovesIdentity() {
        Identity identity = Identity.create(UUID.randomUUID(), IdentityStatus.ACTIVE);
        repository.save(identity);

        repository.deleteById(identity.id());

        assertThat(repository.findById(identity.id())).isEmpty();
    }
}
