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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The email addresses identities sign in with, as the admin endpoints read them. who's
 * autoconfiguration supplies one, an {@link EmailLinkIdentityEmails}, when email sign-in is on;
 * without it the endpoints show no addresses and refuse a search by address.
 */
public interface IdentityEmails {

  /**
   * Finds the identity that signs in with an address, matched as email sign-in matches it.
   *
   * @param address the address, as typed
   * @return the identity's id, or empty if no identity signs in with it
   */
  Optional<UUID> findIdentityId(String address);

  /**
   * Lists an identity's addresses.
   *
   * @param identityId the identity
   * @return its addresses, in any order (never null; empty if none)
   */
  List<String> addressesOf(UUID identityId);
}
