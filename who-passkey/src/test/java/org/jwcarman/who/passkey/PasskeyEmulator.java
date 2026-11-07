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

import org.springframework.security.web.webauthn.api.AuthenticatorAttestationResponse;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.ImmutableAuthenticationExtensionsClientOutputs;
import org.springframework.security.web.webauthn.api.PublicKeyCredential;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialType;
import org.springframework.security.web.webauthn.management.ImmutableRelyingPartyRegistrationRequest;
import org.springframework.security.web.webauthn.management.RelyingPartyPublicKey;
import org.springframework.security.web.webauthn.management.RelyingPartyRegistrationRequest;

/** A browser with one emulated authenticator, speaking Spring's WebAuthn types. */
final class PasskeyEmulator {

  private final EmulatedAuthenticator authenticator;
  private final boolean userVerification;

  private PasskeyEmulator(String origin, boolean userVerification) {
    this.authenticator = new EmulatedAuthenticator(origin);
    this.userVerification = userVerification;
  }

  /** A device that honours the server's options. */
  static PasskeyEmulator honest(String origin) {
    return new PasskeyEmulator(origin, true);
  }

  /** A device that answers without user verification, whatever the options require. */
  static PasskeyEmulator withoutUserVerification(String origin) {
    return new PasskeyEmulator(origin, false);
  }

  /** Answers the options, as {@code POST /webauthn/register} would carry it. */
  RelyingPartyRegistrationRequest register(
      PublicKeyCredentialCreationOptions options, String label) {
    EmulatedAuthenticator.Attestation attestation =
        authenticator.create(
            options.getRp().getId(),
            options.getRp().getName(),
            options.getUser().getId().getBytes(),
            options.getUser().getName(),
            options.getChallenge().getBytes(),
            options.getAuthenticatorSelection().getResidentKey().getValue(),
            userVerification,
            options.getExcludeCredentials().stream().map(d -> d.getId().getBytes()).toList());
    Bytes rawId = new Bytes(attestation.rawId());
    PublicKeyCredential<AuthenticatorAttestationResponse> credential =
        PublicKeyCredential.<AuthenticatorAttestationResponse>builder()
            .id(rawId.toBase64UrlString())
            .rawId(rawId)
            .type(PublicKeyCredentialType.PUBLIC_KEY)
            .response(
                AuthenticatorAttestationResponse.builder()
                    .attestationObject(new Bytes(attestation.attestationObject()))
                    .clientDataJSON(new Bytes(attestation.clientDataJson()))
                    .build())
            .clientExtensionResults(new ImmutableAuthenticationExtensionsClientOutputs())
            .build();
    return new ImmutableRelyingPartyRegistrationRequest(
        options, new RelyingPartyPublicKey(credential, label));
  }
}
