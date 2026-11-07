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
package org.jwcarman.who.apikey;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

import org.jwcarman.who.core.crypto.MessageDigests;
import org.jwcarman.who.core.crypto.Secrets;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.id.Identifiers;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for creating and managing API key credentials.
 *
 * <p>Each key is a prefixed opaque string ({@code who_<43 URL-safe Base64 chars>}) carrying 256
 * random bits. Only the SHA-256 hash of the raw key is stored — the raw key is returned once and
 * never persisted.
 */
public class ApiKeyService {

  private final ApiKeyCredentialRepository apiKeyCredentialRepository;
  private final CredentialIdentityRepository credentialIdentityRepository;

  /**
   * Creates a new {@code ApiKeyService}.
   *
   * @param apiKeyCredentialRepository repository for API key credentials
   * @param credentialIdentityRepository repository for linking credentials to identities
   */
  public ApiKeyService(
      ApiKeyCredentialRepository apiKeyCredentialRepository,
      CredentialIdentityRepository credentialIdentityRepository) {
    this.apiKeyCredentialRepository =
        requireNonNull(apiKeyCredentialRepository, "apiKeyCredentialRepository must not be null");
    this.credentialIdentityRepository =
        requireNonNull(
            credentialIdentityRepository, "credentialIdentityRepository must not be null");
  }

  /**
   * Generates a new API key for the given identity, stores its hash, and returns the raw key.
   *
   * <p>This is the only opportunity to retrieve the raw key — it is not stored anywhere.
   *
   * @param identity the identity to link the new credential to
   * @param name a human-readable label for this key (e.g. "Production server")
   * @return the raw API key (e.g. {@code who_...}), never stored
   */
  public String create(Identity identity, String name) {
    requireNonNull(identity, "identity must not be null");
    requireNonNull(name, "name must not be null");

    String rawKey = "who_" + Secrets.token();
    String keyHash = sha256Hex(rawKey);

    ApiKeyCredential credential = new ApiKeyCredential(Identifiers.uuid(), name, keyHash);
    apiKeyCredentialRepository.save(credential);
    credentialIdentityRepository.link(credential.id(), identity.id());

    return rawKey;
  }

  /**
   * Revokes an API key: deletes the credential and its identity link, so the key stops resolving.
   *
   * @param credentialId the id of the API key credential
   */
  @Transactional
  public void revoke(UUID credentialId) {
    requireNonNull(credentialId, "credentialId must not be null");
    // Only unlink when an API key was actually deleted, so another credential's link is never
    // touched.
    if (apiKeyCredentialRepository.deleteById(credentialId)) {
      credentialIdentityRepository.unlink(credentialId);
    }
  }

  static String sha256Hex(String input) {
    return MessageDigests.sha256Hex(input);
  }
}
