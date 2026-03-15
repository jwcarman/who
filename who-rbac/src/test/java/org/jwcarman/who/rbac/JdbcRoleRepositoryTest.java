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
package org.jwcarman.who.rbac;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcRoleRepositoryTest extends AbstractRbacTest {

    @Autowired
    private JdbcRoleRepository repository;

    @Test
    void savesNewRoleAndRetrievesItById() {
        Role role = Role.create("ADMIN");
        repository.save(role);

        assertThat(repository.findById(role.id())).isPresent()
                .hasValueSatisfying(found -> {
                    assertThat(found.id()).isEqualTo(role.id());
                    assertThat(found.name()).isEqualTo("ADMIN");
                });
    }

    @Test
    void savesNewRoleAndRetrievesItByName() {
        Role role = Role.create("USER");
        repository.save(role);

        assertThat(repository.findByName("USER")).isPresent()
                .hasValueSatisfying(found -> assertThat(found.id()).isEqualTo(role.id()));
    }

    @Test
    void upsertUpdatesNameOnConflict() {
        Role original = Role.create("OLD_NAME");
        repository.save(original);
        Role updated = new Role(original.id(), "NEW_NAME");
        repository.save(updated);

        assertThat(repository.findById(original.id())).isPresent()
                .hasValueSatisfying(found -> assertThat(found.name()).isEqualTo("NEW_NAME"));
    }

    @Test
    void existsByIdReturnsTrueWhenRoleExists() {
        Role role = Role.create("EXISTS");
        repository.save(role);

        assertThat(repository.existsById(role.id())).isTrue();
    }

    @Test
    void existsByIdReturnsFalseWhenRoleAbsent() {
        assertThat(repository.existsById(UUID.randomUUID())).isFalse();
    }

    @Test
    void deleteByIdRemovesRole() {
        Role role = Role.create("TO_DELETE");
        repository.save(role);

        repository.deleteById(role.id());

        assertThat(repository.findById(role.id())).isEmpty();
    }

    @Test
    void findByIdReturnsEmptyWhenNotFound() {
        assertThat(repository.findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    void findByNameReturnsEmptyWhenNotFound() {
        assertThat(repository.findByName("NONEXISTENT")).isEmpty();
    }
}
