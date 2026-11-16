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
package org.jwcarman.who.autoconfigure;

import java.time.Duration;
import java.util.Set;

import org.jwcarman.who.apikey.ApiKeyService;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.rbac.RbacService;
import org.jwcarman.who.rbac.Role;

/** Keys for tests: a key needs a scope its owner holds, so the owner gets one first. */
final class TestApiKeys {

  /** The permission test keys are scoped to. */
  static final String PERMISSION = "test.api";

  /** The role that holds {@link #PERMISSION}. */
  static final String ROLE = "api-key-holder";

  private TestApiKeys() {}

  /**
   * Gives an identity {@link #PERMISSION}, through a role made on first use.
   *
   * @param rbac the RBAC service
   * @param identity the future key's owner
   */
  static synchronized void grant(RbacService rbac, Identity identity) {
    Role role =
        rbac.findRole(ROLE)
            .orElseGet(
                () -> {
                  Role created = rbac.createRole(ROLE);
                  rbac.addPermissionToRole(created, PERMISSION);
                  return created;
                });
    rbac.assignRoleToIdentity(identity, role);
  }

  /**
   * Makes a 30-day key scoped to {@link #PERMISSION}; the owner must have been {@link #grant
   * granted} it.
   *
   * @param keys the API-key service
   * @param identity the owner
   * @param name the key's name
   * @return the raw key
   */
  static String create(ApiKeyService keys, Identity identity, String name) {
    return keys.create(identity, name, Duration.ofDays(30), Set.of(PERMISSION)).key();
  }
}
