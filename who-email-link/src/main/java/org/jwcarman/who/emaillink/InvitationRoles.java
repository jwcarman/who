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

import java.util.Set;
import java.util.UUID;

import org.jwcarman.who.core.domain.Identity;

/**
 * Gives an invited identity its roles, for {@link InvitationService}. Roles live in {@code
 * who-rbac}, which {@code who-email-link} does not need: who's autoconfiguration supplies an {@link
 * RbacInvitationRoles} when {@code who-rbac} is on the classpath, and without one an invitation may
 * carry no roles.
 */
public interface InvitationRoles {

  /**
   * Assigns the named roles to the identity, inside the caller's transaction, so a failure rolls
   * the invitation back.
   *
   * @param identity the invited identity
   * @param roleNames the names of the roles to assign; not empty
   * @throws InvalidInvitationException if a name names no role
   */
  void assign(Identity identity, Set<String> roleNames);

  /**
   * Names the roles the identity holds now, for an invitation sent again ({@link
   * InvitationService#resend(UUID)}). Roles that can only assign name none.
   *
   * @param identityId the invited identity's id
   * @return the names of its roles; empty for none
   */
  default Set<String> namesOf(UUID identityId) {
    return Set.of();
  }
}
