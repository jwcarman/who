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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;

class RbacServiceLookupTest extends AbstractRbacTest {

  enum Tier {
    GOLD,
    UNKNOWN_TIER
  }

  @Autowired private RbacService rbacService;

  @Autowired private IdentityRepository identityRepository;

  @Test
  void findRoleIsEmptyForAnUnknownName() {
    assertThat(rbacService.findRole("NO_SUCH_ROLE")).isEmpty();
  }

  @Test
  void findRoleByName() {
    Role role = rbacService.createRole("FINDABLE");

    assertThat(rbacService.findRole("FINDABLE")).contains(role);
  }

  @Test
  void findRoleByEnum() {
    Role role = rbacService.createRole(Tier.GOLD);

    assertThat(rbacService.findRole(Tier.GOLD)).contains(role);
    assertThat(rbacService.findRole(Tier.UNKNOWN_TIER)).isEmpty();
  }

  @Test
  void listRolesIsSortedByName() {
    rbacService.createRole("LIST_C");
    rbacService.createRole("LIST_A");
    rbacService.createRole("LIST_B");

    List<String> names = rbacService.listRoles().stream().map(Role::name).toList();

    assertThat(names).containsSubsequence("LIST_A", "LIST_B", "LIST_C").isSorted();
  }

  @Test
  void findIdentityIdsWithRole() {
    Role role = rbacService.createRole("HOLDERS");
    Role other = rbacService.createRole("NOT_HELD");
    List<Identity> holders = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      holders.add(identityRepository.save(Identity.create()));
    }
    // Assign in descending order so that unsorted storage order differs from the expected order.
    holders.sort(Comparator.comparing((Identity i) -> i.id().toString()).reversed());
    for (Identity holder : holders) {
      rbacService.assignRoleToIdentity(holder, role);
    }
    Identity bystander = identityRepository.save(Identity.create());
    rbacService.assignRoleToIdentity(bystander, other);

    List<UUID> expected =
        holders.stream().map(Identity::id).sorted(Comparator.comparing(UUID::toString)).toList();

    assertThat(rbacService.findIdentityIdsWithRole(role)).containsExactlyElementsOf(expected);
    assertThat(rbacService.findIdentityIdsWithRole(Role.create("UNSAVED"))).isEmpty();
  }

  @Test
  void duplicateRoleThrowsRoleAlreadyExists() {
    rbacService.createRole("DUPLICATE");

    assertThatThrownBy(() -> rbacService.createRole("DUPLICATE"))
        .isInstanceOf(RoleAlreadyExistsException.class)
        .hasMessageNotContaining("DUPLICATE");
  }

  @Test
  void duplicateRoleIsStillAnIllegalArgument() {
    rbacService.createRole(Tier.GOLD);

    assertThatThrownBy(() -> rbacService.createRole(Tier.GOLD))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void concurrentDuplicateIsReportedAsRoleAlreadyExists(
      @Autowired JdbcRoleRepository roleRepository,
      @Autowired RolePermissionRepository permissions,
      @Autowired IdentityRoleRepository identityRoles) {
    roleRepository.save(Role.create("RACED"));
    RoleRepository missesTheCheck =
        new RoleRepository() {
          @Override
          public Optional<Role> findById(UUID id) {
            return roleRepository.findById(id);
          }

          @Override
          public Optional<Role> findByName(String name) {
            return Optional.empty();
          }

          @Override
          public List<Role> findAll() {
            return roleRepository.findAll();
          }

          @Override
          public Role save(Role role) {
            return roleRepository.save(role);
          }

          @Override
          public boolean existsById(UUID id) {
            return roleRepository.existsById(id);
          }

          @Override
          public void deleteById(UUID id) {
            roleRepository.deleteById(id);
          }
        };
    RbacService racing = new RbacService(missesTheCheck, permissions, identityRoles);

    assertThatThrownBy(() -> racing.createRole("RACED"))
        .isInstanceOf(RoleAlreadyExistsException.class)
        .hasCauseInstanceOf(DuplicateKeyException.class);
  }
}
