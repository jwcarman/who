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

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import org.jwcarman.who.core.crypto.MessageDigests;
import org.jwcarman.who.core.crypto.Secrets;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.event.CredentialAdded;
import org.jwcarman.who.core.event.CredentialKind;
import org.jwcarman.who.core.event.CredentialRevoked;
import org.jwcarman.who.core.event.WhoEventPublisher;
import org.jwcarman.who.core.id.Identifiers;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for creating and managing API key credentials.
 *
 * <p>Each key is a prefixed opaque string ({@code who_<43 URL-safe Base64 chars>}) carrying 256
 * random bits. Only the SHA-256 hash of the raw key is stored — the raw key is returned once and
 * never persisted.
 *
 * <p>Creating a key publishes {@link CredentialAdded} and revoking one publishes {@link
 * CredentialRevoked}, each with the key's name as its label. Neither event carries the key or its
 * hash.
 */
public class ApiKeyService {

  private final ApiKeyCredentialRepository apiKeyCredentialRepository;
  private final CredentialIdentityRepository credentialIdentityRepository;
  private final WhoEventPublisher eventPublisher;
  private final Clock clock;

  /**
   * Creates a new {@code ApiKeyService} that publishes no events.
   *
   * @param apiKeyCredentialRepository repository for API key credentials
   * @param credentialIdentityRepository repository for linking credentials to identities
   */
  public ApiKeyService(
      ApiKeyCredentialRepository apiKeyCredentialRepository,
      CredentialIdentityRepository credentialIdentityRepository) {
    this(
        apiKeyCredentialRepository,
        credentialIdentityRepository,
        WhoEventPublisher.NOOP,
        Clock.systemUTC());
  }

  /**
   * Creates a new {@code ApiKeyService}.
   *
   * @param apiKeyCredentialRepository repository for API key credentials
   * @param credentialIdentityRepository repository for linking credentials to identities
   * @param eventPublisher where {@link CredentialAdded} and {@link CredentialRevoked} are published
   * @param clock stamps the events
   */
  public ApiKeyService(
      ApiKeyCredentialRepository apiKeyCredentialRepository,
      CredentialIdentityRepository credentialIdentityRepository,
      WhoEventPublisher eventPublisher,
      Clock clock) {
    this.apiKeyCredentialRepository =
        requireNonNull(apiKeyCredentialRepository, "apiKeyCredentialRepository must not be null");
    this.credentialIdentityRepository =
        requireNonNull(
            credentialIdentityRepository, "credentialIdentityRepository must not be null");
    this.eventPublisher = requireNonNull(eventPublisher, "eventPublisher must not be null");
    this.clock = requireNonNull(clock, "clock must not be null");
  }

  /**
   * Generates a new API key for the given identity, stores its hash, and returns the raw key.
   *
   * <p>This is the only opportunity to retrieve the raw key — it is not stored anywhere. Publishes
   * {@link CredentialAdded} labelled with {@code name}.
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
    eventPublisher.publish(
        new CredentialAdded(
            clock.instant(), identity.id(), credential.id(), credential.kind(), name));

    return rawKey;
  }

  /**
   * Revokes an API key: deletes the credential and its identity link, so the key stops resolving,
   * then publishes {@link CredentialRevoked} if it was linked to an identity. Does nothing for an
   * unknown id.
   *
   * @param credentialId the id of the API key credential
   */
  @Transactional
  public void revoke(UUID credentialId) {
    requireNonNull(credentialId, "credentialId must not be null");
    // looked up before deleting and unlinking, which remove the only record of name and owner
    Optional<String> name =
        apiKeyCredentialRepository.findById(credentialId).map(ApiKeyCredential::name);
    Optional<UUID> owner = credentialIdentityRepository.findIdentityIdByCredentialId(credentialId);
    // Only unlink when an API key was actually deleted, so another credential's link is never
    // touched.
    if (apiKeyCredentialRepository.deleteById(credentialId)) {
      credentialIdentityRepository.unlink(credentialId);
      owner.ifPresent(
          identityId ->
              eventPublisher.publish(
                  new CredentialRevoked(
                      clock.instant(),
                      identityId,
                      credentialId,
                      CredentialKind.API_KEY,
                      name.orElse(null))));
    }
  }

  static String sha256Hex(String input) {
    return MessageDigests.sha256Hex(input);
  }
}
