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

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.springframework.beans.factory.annotation.Autowired;

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
    Identity first = identityRepository.save(Identity.create());
    Identity second = identityRepository.save(Identity.create());
    Identity bystander = identityRepository.save(Identity.create());
    rbacService.assignRoleToIdentity(first, role);
    rbacService.assignRoleToIdentity(second, role);
    rbacService.assignRoleToIdentity(bystander, other);

    List<UUID> holders = rbacService.findIdentityIdsWithRole(role);

    assertThat(holders).containsExactlyInAnyOrder(first.id(), second.id());
    assertThat(holders.stream().map(UUID::toString).toList()).isSorted();
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
}
