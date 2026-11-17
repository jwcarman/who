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
package org.jwcarman.who.admin;

/**
 * The permissions who's admin endpoints check. who defines them but grants them to no one: an
 * application assigns them to its own roles, as it would any permission, for example with {@code
 * RbacService.addPermissionToRole(role, WhoAdminPermissions.IDENTITIES_READ)}. An API key may use
 * one when its scopes include it.
 */
public final class WhoAdminPermissions {

  /** Lists and reads identities, and lists the roles that exist. */
  public static final String IDENTITIES_READ = "who.identities.read";

  /** Deactivates and reactivates identities, and assigns and removes their roles. */
  public static final String IDENTITIES_MANAGE = "who.identities.manage";

  private WhoAdminPermissions() {}
}
