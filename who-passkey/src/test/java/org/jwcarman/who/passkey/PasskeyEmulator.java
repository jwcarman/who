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

import java.util.ArrayList;
import java.util.List;

import org.springframework.security.web.webauthn.api.AuthenticatorAssertionResponse;
import org.springframework.security.web.webauthn.api.AuthenticatorAttestationResponse;
import org.springframework.security.web.webauthn.api.AuthenticatorResponse;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.ImmutableAuthenticationExtensionsClientOutputs;
import org.springframework.security.web.webauthn.api.PublicKeyCredential;
import org.springframework.security.web.webauthn.api.PublicKeyCredential.PublicKeyCredentialBuilder;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialDescriptor;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialType;
import org.springframework.security.web.webauthn.management.ImmutableRelyingPartyRegistrationRequest;
import org.springframework.security.web.webauthn.management.RelyingPartyAuthenticationRequest;
import org.springframework.security.web.webauthn.management.RelyingPartyPublicKey;
import org.springframework.security.web.webauthn.management.RelyingPartyRegistrationRequest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

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
        credential(
            rawId,
            AuthenticatorAttestationResponse.builder()
                .attestationObject(new Bytes(attestation.attestationObject()))
                .clientDataJSON(new Bytes(attestation.clientDataJson()))
                .build());
    return new ImmutableRelyingPartyRegistrationRequest(
        options, new RelyingPartyPublicKey(credential, label));
  }

  /**
   * Answers the request options with a credential this device registered, as {@code POST
   * /login/webauthn} would carry it. The device verifies the user unless it was made {@link
   * #withoutUserVerification(String) without user verification}.
   */
  RelyingPartyAuthenticationRequest authenticate(PublicKeyCredentialRequestOptions options) {
    return authenticate(options, userVerification);
  }

  /**
   * Answers the request options without verifying the user, even if this device registered with
   * verification and the options require it.
   */
  RelyingPartyAuthenticationRequest authenticateWithoutUserVerification(
      PublicKeyCredentialRequestOptions options) {
    return authenticate(options, false);
  }

  /**
   * Answers the JSON of {@code POST /webauthn/authenticate/options} as {@code
   * navigator.credentials.get()} would, and returns the JSON body for {@code POST /login/webauthn}.
   */
  String signIn(String requestOptionsJson) {
    JsonMapper json = JsonMapper.builder().build();
    JsonNode options = json.readTree(requestOptionsJson);
    List<PublicKeyCredentialDescriptor> allowed = new ArrayList<>();
    for (JsonNode descriptor : options.path("allowCredentials")) {
      allowed.add(
          PublicKeyCredentialDescriptor.builder()
              .id(Bytes.fromBase64(descriptor.path("id").asString()))
              .build());
    }
    PublicKeyCredential<AuthenticatorAssertionResponse> credential =
        authenticate(
                PublicKeyCredentialRequestOptions.builder()
                    .challenge(Bytes.fromBase64(options.path("challenge").asString()))
                    .rpId(options.path("rpId").asString())
                    .allowCredentials(allowed)
                    .build())
            .getPublicKey();
    AuthenticatorAssertionResponse assertion = credential.getResponse();
    ObjectNode response = json.createObjectNode();
    response.put("authenticatorData", assertion.getAuthenticatorData().toBase64UrlString());
    response.put("clientDataJSON", assertion.getClientDataJSON().toBase64UrlString());
    response.put("signature", assertion.getSignature().toBase64UrlString());
    if (assertion.getUserHandle() != null) {
      response.put("userHandle", assertion.getUserHandle().toBase64UrlString());
    }
    ObjectNode body = json.createObjectNode();
    body.put("id", credential.getId());
    body.put("rawId", credential.getRawId().toBase64UrlString());
    body.put("type", "public-key");
    body.set("response", response);
    body.set("clientExtensionResults", json.createObjectNode());
    body.put("authenticatorAttachment", "platform");
    return json.writeValueAsString(body);
  }

  private RelyingPartyAuthenticationRequest authenticate(
      PublicKeyCredentialRequestOptions options, boolean userVerified) {
    EmulatedAuthenticator.Assertion assertion =
        authenticator.get(
            options.getRpId(),
            options.getChallenge().getBytes(),
            options.getAllowCredentials().stream().map(d -> d.getId().getBytes()).toList(),
            userVerified);
    Bytes rawId = new Bytes(assertion.rawId());
    PublicKeyCredential<AuthenticatorAssertionResponse> credential =
        credential(
            rawId,
            AuthenticatorAssertionResponse.builder()
                .authenticatorData(new Bytes(assertion.authenticatorData()))
                .clientDataJSON(new Bytes(assertion.clientDataJson()))
                .signature(new Bytes(assertion.signature()))
                .userHandle(
                    assertion.userHandle() != null ? new Bytes(assertion.userHandle()) : null)
                .build());
    return new RelyingPartyAuthenticationRequest(options, credential);
  }

  /**
   * A public-key credential with the given id and response. Spring's builder setters return the raw
   * builder type, so chaining them loses {@code R}; setting each on the typed builder keeps it.
   */
  static <R extends AuthenticatorResponse> PublicKeyCredential<R> credential(
      Bytes rawId, R response) {
    PublicKeyCredentialBuilder<R> builder = PublicKeyCredential.builder();
    builder.id(rawId.toBase64UrlString());
    builder.rawId(rawId);
    builder.type(PublicKeyCredentialType.PUBLIC_KEY);
    builder.response(response);
    builder.clientExtensionResults(new ImmutableAuthenticationExtensionsClientOutputs());
    return builder.build();
  }
}
