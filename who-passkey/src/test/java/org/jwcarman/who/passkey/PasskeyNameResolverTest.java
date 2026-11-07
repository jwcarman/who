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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class PasskeyNameResolverTest {

  private final UUID identityId = UUID.randomUUID();
  private final UUID emailCredentialId = UUID.randomUUID();
  private final UUID otherCredentialId = UUID.randomUUID();

  private final Map<UUID, String> credentialAddresses =
      Map.of(emailCredentialId, "session@example.com");
  private final Map<UUID, String> oldestAddresses = Map.of(identityId, "oldest@example.com");

  private final PasskeyNameResolver resolver =
      PasskeyNameResolver.preferring(
          id -> Optional.ofNullable(credentialAddresses.get(id)),
          id -> Optional.ofNullable(oldestAddresses.get(id)));

  @Test
  void nameIsTheSessionsEmailThenTheOldestThenTheIdentityId() {
    // 1. The session signed in with an email credential: its address.
    assertThat(resolver.nameFor(identityId, Optional.of(emailCredentialId)))
        .isEqualTo("session@example.com");
    // 2. The session's credential is not an email credential (or there is none): the oldest.
    assertThat(resolver.nameFor(identityId, Optional.of(otherCredentialId)))
        .isEqualTo("oldest@example.com");
    assertThat(resolver.nameFor(identityId, Optional.empty())).isEqualTo("oldest@example.com");
    // 3. The identity has no email credential: the identity id.
    UUID noEmail = UUID.randomUUID();
    assertThat(resolver.nameFor(noEmail, Optional.of(otherCredentialId)))
        .isEqualTo(noEmail.toString());
  }

  @Test
  void identityIdResolverAlwaysAnswersTheIdentityId() {
    assertThat(PasskeyNameResolver.identityId().nameFor(identityId, Optional.of(emailCredentialId)))
        .isEqualTo(identityId.toString());
  }
}
