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
package org.jwcarman.who.passkey;

import java.util.List;
import java.util.UUID;

import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.core.service.LastCredentialGuard;
import org.jwcarman.who.core.service.SignInCredentialCounter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/** The last-credential guard for tests whose identities have a passkey and nothing else. */
final class LastCredentialGuards {

  private LastCredentialGuards() {}

  /**
   * A guard that counts the identity's passkeys and one more credential, an email address these
   * tests do not register, so removing a passkey is never the identity's last.
   */
  static LastCredentialGuard withAnotherCredential(
      IdentityRepository identityRepository,
      JdbcClient jdbcClient,
      PlatformTransactionManager transactionManager) {
    SignInCredentialCounter email =
        new SignInCredentialCounter() {
          @Override
          public String kind() {
            return "email";
          }

          @Override
          public long count(UUID identityId) {
            return 1;
          }
        };
    return new LastCredentialGuard(
        identityRepository,
        List.of(new PasskeySignInCredentialCounter(jdbcClient), email),
        transactionManager);
  }
}
