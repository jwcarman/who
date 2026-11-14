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
package org.jwcarman.who.nativetests.support;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import com.webauthn4j.data.AttestationConveyancePreference;
import com.webauthn4j.data.AuthenticatorAssertionResponse;
import com.webauthn4j.data.AuthenticatorAttestationResponse;
import com.webauthn4j.data.AuthenticatorSelectionCriteria;
import com.webauthn4j.data.PublicKeyCredential;
import com.webauthn4j.data.PublicKeyCredentialCreationOptions;
import com.webauthn4j.data.PublicKeyCredentialDescriptor;
import com.webauthn4j.data.PublicKeyCredentialParameters;
import com.webauthn4j.data.PublicKeyCredentialRequestOptions;
import com.webauthn4j.data.PublicKeyCredentialRpEntity;
import com.webauthn4j.data.PublicKeyCredentialType;
import com.webauthn4j.data.PublicKeyCredentialUserEntity;
import com.webauthn4j.data.ResidentKeyRequirement;
import com.webauthn4j.data.UserVerificationRequirement;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.data.extension.client.AuthenticationExtensionClientOutput;
import com.webauthn4j.data.extension.client.RegistrationExtensionClientOutput;
import com.webauthn4j.test.authenticator.webauthn.NoneAttestationAuthenticator;
import com.webauthn4j.test.authenticator.webauthn.WebAuthnAuthenticatorAdaptor;
import com.webauthn4j.test.client.ClientPlatform;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * One emulated device (webauthn4j-test): a browser at {@code origin} with its own authenticator,
 * speaking the JSON of Spring's WebAuthn endpoints. Copied from who-autoconfigure's test
 * PasskeyDevice, itself a minimal copy of who-passkey's test emulator, which works on Spring's
 * types rather than on JSON.
 */
public final class PasskeyDevice {

  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

  private final JsonMapper json = new JsonMapper();
  private final ClientPlatform platform;
  private String credentialId;

  public PasskeyDevice(String origin) {
    this.platform =
        new ClientPlatform(
            new Origin(origin),
            new WebAuthnAuthenticatorAdaptor(new NoneAttestationAuthenticator()));
  }

  /** The base64url id of the credential this device created last. */
  public String credentialId() {
    return credentialId;
  }

  /**
   * Answers {@code POST /webauthn/register/options} as {@code navigator.credentials.create()}
   * would, and returns the body for {@code POST /webauthn/register}.
   */
  public String register(String creationOptionsJson, String label) {
    JsonNode options = json.readTree(creationOptionsJson);
    JsonNode rp = options.path("rp");
    JsonNode user = options.path("user");
    List<PublicKeyCredentialDescriptor> excluded = new ArrayList<>();
    for (JsonNode descriptor : options.path("excludeCredentials")) {
      excluded.add(
          new PublicKeyCredentialDescriptor(
              PublicKeyCredentialType.PUBLIC_KEY, bytes(descriptor.path("id")), null));
    }
    PublicKeyCredentialCreationOptions creation =
        new PublicKeyCredentialCreationOptions(
            new PublicKeyCredentialRpEntity(rp.path("id").asString(), rp.path("name").asString()),
            new PublicKeyCredentialUserEntity(
                bytes(user.path("id")),
                user.path("name").asString(),
                user.path("displayName").asString()),
            new DefaultChallenge(bytes(options.path("challenge"))),
            List.of(
                new PublicKeyCredentialParameters(
                    PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.ES256)),
            null,
            excluded,
            new AuthenticatorSelectionCriteria(
                null,
                ResidentKeyRequirement.create(
                    options.path("authenticatorSelection").path("residentKey").asString()),
                UserVerificationRequirement.REQUIRED),
            AttestationConveyancePreference.NONE,
            null);
    PublicKeyCredential<AuthenticatorAttestationResponse, RegistrationExtensionClientOutput>
        created = platform.create(creation);
    credentialId = ENCODER.encodeToString(created.getRawId());

    ObjectNode response = json.createObjectNode();
    response.put(
        "attestationObject", ENCODER.encodeToString(created.getResponse().getAttestationObject()));
    response.put(
        "clientDataJSON", ENCODER.encodeToString(created.getResponse().getClientDataJSON()));
    ObjectNode credential = credential(created.getRawId());
    credential.set("response", response);
    ObjectNode publicKey = json.createObjectNode();
    publicKey.set("credential", credential);
    publicKey.put("label", label);
    ObjectNode body = json.createObjectNode();
    body.set("publicKey", publicKey);
    return json.writeValueAsString(body);
  }

  /**
   * Answers {@code POST /webauthn/authenticate/options} as {@code navigator.credentials.get()}
   * would, verifying the user, and returns the body for {@code POST /login/webauthn}.
   */
  public String signIn(String requestOptionsJson) {
    return signIn(requestOptionsJson, null);
  }

  /**
   * As {@link #signIn(String)}, with the assertion's user handle replaced. The user handle is not
   * covered by the signature, so a client can send any value.
   */
  public String signIn(String requestOptionsJson, byte[] userHandle) {
    JsonNode options = json.readTree(requestOptionsJson);
    List<PublicKeyCredentialDescriptor> allowed = new ArrayList<>();
    for (JsonNode descriptor : options.path("allowCredentials")) {
      allowed.add(
          new PublicKeyCredentialDescriptor(
              PublicKeyCredentialType.PUBLIC_KEY, bytes(descriptor.path("id")), null));
    }
    PublicKeyCredentialRequestOptions request =
        new PublicKeyCredentialRequestOptions(
            new DefaultChallenge(bytes(options.path("challenge"))),
            null,
            options.path("rpId").asString(),
            allowed,
            UserVerificationRequirement.REQUIRED,
            null);
    PublicKeyCredential<AuthenticatorAssertionResponse, AuthenticationExtensionClientOutput>
        asserted = platform.get(request);

    ObjectNode response = json.createObjectNode();
    response.put(
        "authenticatorData", ENCODER.encodeToString(asserted.getResponse().getAuthenticatorData()));
    response.put(
        "clientDataJSON", ENCODER.encodeToString(asserted.getResponse().getClientDataJSON()));
    response.put("signature", ENCODER.encodeToString(asserted.getResponse().getSignature()));
    byte[] handle = userHandle != null ? userHandle : asserted.getResponse().getUserHandle();
    if (handle != null) {
      response.put("userHandle", ENCODER.encodeToString(handle));
    }
    ObjectNode credential = credential(asserted.getRawId());
    credential.set("response", response);
    return json.writeValueAsString(credential);
  }

  /** The user handle ({@code user.id}) in the creation options. */
  public byte[] userHandle(String creationOptionsJson) {
    return bytes(json.readTree(creationOptionsJson).path("user").path("id"));
  }

  private ObjectNode credential(byte[] rawId) {
    ObjectNode credential = json.createObjectNode();
    String id = ENCODER.encodeToString(rawId);
    credential.put("id", id);
    credential.put("rawId", id);
    credential.put("type", "public-key");
    credential.set("clientExtensionResults", json.createObjectNode());
    credential.put("authenticatorAttachment", "platform");
    return credential;
  }

  private static byte[] bytes(JsonNode base64Url) {
    return DECODER.decode(base64Url.asString());
  }
}
