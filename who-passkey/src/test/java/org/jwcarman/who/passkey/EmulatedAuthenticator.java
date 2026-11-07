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

import com.webauthn4j.converter.AttestationObjectConverter;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.data.AttestationConveyancePreference;
import com.webauthn4j.data.AuthenticatorAttestationResponse;
import com.webauthn4j.data.AuthenticatorSelectionCriteria;
import com.webauthn4j.data.PublicKeyCredential;
import com.webauthn4j.data.PublicKeyCredentialCreationOptions;
import com.webauthn4j.data.PublicKeyCredentialDescriptor;
import com.webauthn4j.data.PublicKeyCredentialParameters;
import com.webauthn4j.data.PublicKeyCredentialRpEntity;
import com.webauthn4j.data.PublicKeyCredentialType;
import com.webauthn4j.data.PublicKeyCredentialUserEntity;
import com.webauthn4j.data.ResidentKeyRequirement;
import com.webauthn4j.data.UserVerificationRequirement;
import com.webauthn4j.data.attestation.AttestationObject;
import com.webauthn4j.data.attestation.authenticator.AuthenticatorData;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.data.extension.authenticator.RegistrationExtensionAuthenticatorOutput;
import com.webauthn4j.data.extension.client.RegistrationExtensionClientOutput;
import com.webauthn4j.test.authenticator.webauthn.NoneAttestationAuthenticator;
import com.webauthn4j.test.authenticator.webauthn.WebAuthnAuthenticatorAdaptor;
import com.webauthn4j.test.client.ClientPlatform;

/**
 * One emulated device (webauthn4j-test): a browser at {@code origin} with its own authenticator.
 * Only webauthn4j types appear here; {@link PasskeyEmulator} converts to and from Spring's.
 */
final class EmulatedAuthenticator {

  /** What the browser posts back after {@code navigator.credentials.create()}. */
  record Attestation(byte[] rawId, byte[] clientDataJson, byte[] attestationObject) {}

  private final ClientPlatform platform;

  EmulatedAuthenticator(String origin) {
    this.platform =
        new ClientPlatform(
            new Origin(origin),
            new WebAuthnAuthenticatorAdaptor(new NoneAttestationAuthenticator()));
  }

  /**
   * Creates a credential, as the device's authenticator would.
   *
   * @param userVerified whether the user was verified; webauthn4j-test always verifies, so for
   *     {@code false} the UV flag is cleared afterwards. With "none" attestation nothing signs the
   *     authenticator data, so the result is what a device that skipped verification would send.
   */
  Attestation create(
      String rpId,
      String rpName,
      byte[] userId,
      String userName,
      byte[] challenge,
      String residentKey,
      boolean userVerified,
      List<byte[]> excludedCredentialIds) {
    PublicKeyCredentialCreationOptions options =
        new PublicKeyCredentialCreationOptions(
            new PublicKeyCredentialRpEntity(rpId, rpName),
            new PublicKeyCredentialUserEntity(userId, userName, userName),
            new DefaultChallenge(challenge),
            List.of(
                new PublicKeyCredentialParameters(
                    PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.ES256)),
            null,
            excludedCredentialIds.stream()
                .map(
                    id ->
                        new PublicKeyCredentialDescriptor(
                            PublicKeyCredentialType.PUBLIC_KEY, id, null))
                .toList(),
            new AuthenticatorSelectionCriteria(
                null,
                ResidentKeyRequirement.create(residentKey),
                UserVerificationRequirement.REQUIRED),
            AttestationConveyancePreference.NONE,
            null);
    PublicKeyCredential<AuthenticatorAttestationResponse, RegistrationExtensionClientOutput>
        credential = platform.create(options);
    byte[] attestationObject = credential.getResponse().getAttestationObject();
    return new Attestation(
        credential.getRawId(),
        credential.getResponse().getClientDataJSON(),
        userVerified ? attestationObject : withoutUserVerification(attestationObject));
  }

  private static byte[] withoutUserVerification(byte[] attestationObject) {
    AttestationObjectConverter converter = new AttestationObjectConverter(new ObjectConverter());
    AttestationObject decoded = converter.convert(attestationObject);
    AuthenticatorData<RegistrationExtensionAuthenticatorOutput> data =
        decoded.getAuthenticatorData();
    AuthenticatorData<RegistrationExtensionAuthenticatorOutput> unverified =
        new AuthenticatorData<>(
            data.getRpIdHash(),
            (byte) (data.getFlags() & ~AuthenticatorData.BIT_UV),
            data.getSignCount(),
            data.getAttestedCredentialData(),
            data.getExtensions());
    return converter.convertToBytes(
        new AttestationObject(unverified, decoded.getAttestationStatement()));
  }
}
