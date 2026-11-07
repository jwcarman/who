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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;

import org.springframework.security.web.webauthn.api.AuthenticatorTransport;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutableCredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCose;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialType;

/** Builds credential records the way Spring's relying-party operations do at registration. */
final class CredentialRecords {

  private CredentialRecords() {}

  /** Saves a new record as a registration does: the only save that inserts. */
  static void register(JdbcWhoPasskeyCredentialRepository repository, CredentialRecord record) {
    JdbcWhoPasskeyCredentialRepository.registering(
        () -> {
          repository.save(record);
          return record;
        });
  }

  static ImmutableCredentialRecord.ImmutableCredentialRecordBuilder registration(
      Bytes userHandle, String label) {
    Instant created = Instant.now().truncatedTo(ChronoUnit.MICROS);
    return ImmutableCredentialRecord.builder()
        .userEntityUserId(userHandle)
        .credentialType(PublicKeyCredentialType.PUBLIC_KEY)
        .credentialId(Bytes.random())
        .publicKey(new ImmutablePublicKeyCose(new byte[] {1, 2, 3, 4}))
        .signatureCount(0)
        .uvInitialized(true)
        .transports(Set.of(AuthenticatorTransport.INTERNAL, AuthenticatorTransport.HYBRID))
        .backupEligible(true)
        .backupState(false)
        .attestationObject(new Bytes(new byte[] {5, 6, 7}))
        .attestationClientDataJSON(new Bytes(new byte[] {8, 9}))
        .created(created)
        .lastUsed(created)
        .label(label);
  }
}
