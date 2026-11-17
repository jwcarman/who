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
package org.jwcarman.who.core.service;

import java.util.UUID;

/**
 * Counts one kind of sign-in credential an identity holds. Each credential module supplies one, and
 * {@link LastCredentialGuard} sums them. API keys are not counted: they sign in a machine, not the
 * person.
 */
public interface SignInCredentialCounter {

  /**
   * The kind this counter counts, such as {@code "email"}, {@code "passkey"} or {@code "account"}.
   *
   * @return the kind
   */
  String kind();

  /**
   * Counts the credentials of this kind that the identity holds.
   *
   * @param identityId the identity
   * @return how many it holds
   */
  long count(UUID identityId);
}
