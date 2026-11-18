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
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.web.webauthn.api.AuthenticatorTransport;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.ImmutableCredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCose;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialType;

/** Registers passkeys for autoconfiguration tests, as a WebAuthn registration would. */
public final class TestPasskeys {

  private TestPasskeys() {}

  /**
   * Registers a passkey for the identity that has never signed in, and returns its credential id.
   *
   * @param users who's passkey user repository
   * @param credentials who's passkey credential repository
   * @param jdbc clears the passkey's last use, which a registration always sets
   * @param identityId the identity
   * @param label the passkey's label
   * @return the passkey's WebAuthn credential id, base64url
   */
  public static String registerNeverUsed(
      JdbcWhoPasskeyUserRepository users,
      JdbcWhoPasskeyCredentialRepository credentials,
      JdbcClient jdbc,
      UUID identityId,
      String label) {
    Instant created = Instant.now().truncatedTo(ChronoUnit.MICROS);
    ImmutableCredentialRecord credential =
        ImmutableCredentialRecord.builder()
            .userEntityUserId(users.create(identityId, identityId.toString()).getId())
            .credentialType(PublicKeyCredentialType.PUBLIC_KEY)
            .credentialId(Bytes.random())
            .publicKey(new ImmutablePublicKeyCose(new byte[] {1, 2, 3, 4}))
            .signatureCount(0)
            .uvInitialized(true)
            .transports(Set.of(AuthenticatorTransport.INTERNAL))
            .backupEligible(true)
            .backupState(false)
            .attestationObject(new Bytes(new byte[] {5, 6, 7}))
            .attestationClientDataJSON(new Bytes(new byte[] {8, 9}))
            .created(created)
            .lastUsed(created)
            .label(label)
            .build();
    JdbcWhoPasskeyCredentialRepository.registering(
        () -> {
          credentials.save(credential);
          return credential;
        });
    String credentialId = credential.getCredentialId().toBase64UrlString();
    jdbc.sql("UPDATE who_passkey_credential SET last_used = NULL WHERE credential_id = :id")
        .param("id", credentialId)
        .update();
    return credentialId;
  }
}
