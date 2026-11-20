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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.rbac.RbacService;
import org.jwcarman.who.rbac.Role;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class InvitationRolesTest {

  private final UUID dana = UUID.randomUUID();

  @Test
  void the_rbac_roles_name_the_identitys_current_roles() {
    RbacService rbac = mock(RbacService.class);
    when(rbac.findRolesForIdentity(dana))
        .thenReturn(List.of(Role.create("editor"), Role.create("member")));

    assertThat(new RbacInvitationRoles(rbac).namesOf(dana))
        .containsExactlyInAnyOrder("editor", "member");
  }

  @Test
  void roles_that_cannot_name_them_name_none() {
    InvitationRoles assignOnly = (identity, roleNames) -> {};

    assertThat(assignOnly.namesOf(dana)).isEqualTo(Set.of());
  }
}
