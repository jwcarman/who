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
package org.jwcarman.who.core.spi;

import java.util.Set;

import org.jwcarman.who.core.domain.Identity;

/**
 * Strategy for resolving permission strings for a given {@link Identity}. {@link
 * org.jwcarman.who.core.service.WhoService} uses exactly one resolver; results from several
 * resolvers are not combined. With the Spring Boot autoconfiguration, the {@code who-rbac} resolver
 * is used when that module is on the classpath, otherwise a fallback that grants no permissions. An
 * application that needs different behaviour defines its own {@code PermissionsResolver} bean,
 * which replaces the provided one; to combine sources, that bean must do the combining itself.
 * Returning an empty set is valid and not an error.
 */
public interface PermissionsResolver {

  /**
   * Resolves the set of permission strings for the given identity.
   *
   * @param identity the authenticated identity
   * @return the set of permission strings (never null; empty is valid)
   */
  Set<String> resolve(Identity identity);
}
