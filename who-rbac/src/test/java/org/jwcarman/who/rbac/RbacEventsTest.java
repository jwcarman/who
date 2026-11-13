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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.event.WhoEvent;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.springframework.beans.factory.annotation.Autowired;

/** The events {@link RbacService} publishes, one per change and none when nothing changed. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class RbacEventsTest extends AbstractRbacTest {

  private static final Instant NOW = Instant.parse("2031-05-06T07:08:09Z");

  @Autowired private RoleRepository roleRepository;
  @Autowired private RolePermissionRepository rolePermissionRepository;
  @Autowired private IdentityRoleRepository identityRoleRepository;
  @Autowired private IdentityRepository identityRepository;

  private final List<WhoEvent> events = new ArrayList<>();
  private RbacService service;

  @BeforeEach
  void build_service_with_a_recording_publisher() {
    service =
        new RbacService(
            roleRepository,
            rolePermissionRepository,
            identityRoleRepository,
            events::add,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Nested
  class Roles {

    @Test
    void create_role_publishes_role_created() {
      Role role = service.createRole("AUDITOR");

      assertThat(events).containsExactly(new RoleCreated(NOW, role.id(), "AUDITOR"));
    }

    @Test
    void creating_a_duplicate_role_publishes_nothing() {
      service.createRole("AUDITOR");
      events.clear();

      assertThatThrownBy(() -> service.createRole("AUDITOR"))
          .isInstanceOf(RoleAlreadyExistsException.class);

      assertThat(events).isEmpty();
    }

    @Test
    void delete_role_publishes_role_deleted() {
      Role role = service.createRole("AUDITOR");
      events.clear();

      service.deleteRole(role);

      assertThat(events).containsExactly(new RoleDeleted(NOW, role.id(), "AUDITOR"));
    }

    @Test
    void deleting_a_missing_role_publishes_nothing() {
      assertThatThrownBy(() -> service.deleteRole(Role.create("GHOST")))
          .isInstanceOf(IllegalArgumentException.class);

      assertThat(events).isEmpty();
    }
  }

  @Nested
  class Permissions {

    @Test
    void add_permission_publishes_role_permission_added() {
      Role role = service.createRole("AUDITOR");
      events.clear();

      service.addPermissionToRole(role, "audit:read");

      assertThat(events)
          .containsExactly(new RolePermissionAdded(NOW, role.id(), "AUDITOR", "audit:read"));
    }

    @Test
    void adding_a_permission_the_role_already_has_publishes_nothing() {
      Role role = service.createRole("AUDITOR");
      service.addPermissionToRole(role, "audit:read");
      events.clear();

      service.addPermissionToRole(role, "audit:read");

      assertThat(events).isEmpty();
    }

    @Test
    void remove_permission_publishes_role_permission_removed() {
      Role role = service.createRole("AUDITOR");
      service.addPermissionToRole(role, "audit:read");
      events.clear();

      service.removePermissionFromRole(role, "audit:read");

      assertThat(events)
          .containsExactly(new RolePermissionRemoved(NOW, role.id(), "AUDITOR", "audit:read"));
    }

    @Test
    void removing_an_absent_permission_publishes_nothing() {
      Role role = service.createRole("AUDITOR");
      events.clear();

      assertThatThrownBy(() -> service.removePermissionFromRole(role, "audit:read"))
          .isInstanceOf(IllegalArgumentException.class);

      assertThat(events).isEmpty();
    }
  }

  @Nested
  class Assignments {

    private Identity identity;
    private Role role;

    @BeforeEach
    void create_an_identity_and_a_role() {
      identity = identityRepository.save(Identity.create());
      role = service.createRole("AUDITOR");
      events.clear();
    }

    @Test
    void assign_role_publishes_role_assigned() {
      service.assignRoleToIdentity(identity, role);

      assertThat(events)
          .containsExactly(new RoleAssigned(NOW, identity.id(), role.id(), "AUDITOR"));
    }

    @Test
    void assign_role_by_name_publishes_role_assigned() {
      service.assignRoleByName(identity, "AUDITOR");

      assertThat(events)
          .containsExactly(new RoleAssigned(NOW, identity.id(), role.id(), "AUDITOR"));
    }

    @Test
    void assigning_an_assigned_role_publishes_nothing() {
      service.assignRoleToIdentity(identity, role);
      events.clear();

      service.assignRoleToIdentity(identity, role);
      service.assignRoleByName(identity, "AUDITOR");

      assertThat(events).isEmpty();
    }

    @Test
    void unassign_role_publishes_role_unassigned() {
      service.assignRoleToIdentity(identity, role);
      events.clear();

      service.removeRoleFromIdentity(identity, role);

      assertThat(events)
          .containsExactly(new RoleUnassigned(NOW, identity.id(), role.id(), "AUDITOR"));
    }

    @Test
    void unassigning_a_role_that_is_not_assigned_publishes_nothing() {
      assertThatThrownBy(() -> service.removeRoleFromIdentity(identity, role))
          .isInstanceOf(IllegalArgumentException.class);

      assertThat(events).isEmpty();
    }
  }
}
