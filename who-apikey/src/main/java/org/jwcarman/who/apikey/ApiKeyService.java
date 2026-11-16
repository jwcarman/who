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
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jwcarman.who.core.crypto.MessageDigests;
import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.event.CredentialAdded;
import org.jwcarman.who.core.event.CredentialKind;
import org.jwcarman.who.core.event.CredentialRevoked;
import org.jwcarman.who.core.event.WhoEventPublisher;
import org.jwcarman.who.core.id.Identifiers;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.spi.PermissionsResolver;
import org.springframework.transaction.annotation.Transactional;

/**
 * Makes, lists, rotates and revokes API keys.
 *
 * <p>Every key expires and holds scopes. The caller gives a lifetime, at most the configured
 * maximum, and a non-empty set of scopes, each a permission the owner holds when the key is made. A
 * key signs in with its scopes intersected with the permissions its owner holds at that moment, so
 * it never does more than its owner. Keys have the {@link ApiKeyFormat} shape. Only the SHA-256
 * hash of a key is stored: the raw key is in the {@link NewApiKey} that {@link #create} or {@link
 * #rotate} answers, and nowhere else.
 *
 * <p>Making a key publishes {@link CredentialAdded} and revoking one publishes {@link
 * CredentialRevoked}, each with the key's name as its label. Neither event carries the key or its
 * hash.
 */
public class ApiKeyService {

  /** The longest a rotated key's predecessor may keep working. */
  /** The longest name or scope a key may have: the width of their columns. */
  static final int MAX_TEXT = 255;

  public static final Duration MAX_OVERLAP = Duration.ofDays(7);

  private static final Comparator<ApiKey> BY_NAME =
      Comparator.comparing(ApiKey::name).thenComparing(ApiKey::createdAt);

  private final ApiKeyCredentialRepository apiKeyCredentialRepository;
  private final CredentialIdentityRepository credentialIdentityRepository;
  private final PermissionsResolver permissionsResolver;
  private final ApiKeyFormat format;
  private final Duration maxLifetime;
  private final WhoEventPublisher eventPublisher;
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param apiKeyCredentialRepository stores the keys
   * @param credentialIdentityRepository links keys to identities
   * @param permissionsResolver what an owner holds, checked against a new key's scopes
   * @param format makes the keys
   * @param maxLifetime the longest lifetime a key may be given; positive
   * @param eventPublisher where the credential events are published
   * @param clock stamps keys and events
   * @throws IllegalArgumentException if {@code maxLifetime} is not positive
   */
  public ApiKeyService(
      ApiKeyCredentialRepository apiKeyCredentialRepository,
      CredentialIdentityRepository credentialIdentityRepository,
      PermissionsResolver permissionsResolver,
      ApiKeyFormat format,
      Duration maxLifetime,
      WhoEventPublisher eventPublisher,
      Clock clock) {
    this.apiKeyCredentialRepository =
        requireNonNull(apiKeyCredentialRepository, "apiKeyCredentialRepository must not be null");
    this.credentialIdentityRepository =
        requireNonNull(
            credentialIdentityRepository, "credentialIdentityRepository must not be null");
    this.permissionsResolver =
        requireNonNull(permissionsResolver, "permissionsResolver must not be null");
    this.format = requireNonNull(format, "format must not be null");
    this.maxLifetime = requireNonNull(maxLifetime, "maxLifetime must not be null");
    if (maxLifetime.isNegative() || maxLifetime.isZero()) {
      throw new IllegalArgumentException("maxLifetime must be positive, got " + maxLifetime);
    }
    this.eventPublisher = requireNonNull(eventPublisher, "eventPublisher must not be null");
    this.clock = requireNonNull(clock, "clock must not be null");
  }

  /**
   * Makes a key for an identity.
   *
   * @param identity the key's owner
   * @param name what the key is for, such as "Build server"; not blank
   * @param lifetime how long the key signs in; positive, and at most the maximum
   * @param scopes the permissions the key may use: not empty, none blank, each held by the owner
   *     now
   * @return the key, the only time it is shown
   * @throws IllegalArgumentException naming the first rule the arguments break
   */
  @Transactional
  public NewApiKey create(Identity identity, String name, Duration lifetime, Set<String> scopes) {
    requireNonNull(identity, "identity must not be null");
    requireNonNull(name, "name must not be null");
    requireNonNull(lifetime, "lifetime must not be null");
    requireNonNull(scopes, "scopes must not be null");
    if (name.isBlank()) {
      throw new IllegalArgumentException("An API key needs a name");
    }
    if (name.length() > MAX_TEXT) {
      throw new IllegalArgumentException(
          "An API key's name must be at most " + MAX_TEXT + " characters");
    }
    if (lifetime.isNegative() || lifetime.isZero()) {
      throw new IllegalArgumentException("An API key's lifetime must be positive");
    }
    if (lifetime.compareTo(maxLifetime) > 0) {
      throw new IllegalArgumentException(
          "An API key's lifetime must be at most " + maxLifetime + ", got " + lifetime);
    }
    if (scopes.isEmpty()) {
      throw new IllegalArgumentException("An API key needs at least one scope");
    }
    if (scopes.stream().anyMatch(scope -> scope == null || scope.isBlank())) {
      throw new IllegalArgumentException("An API key's scopes must not be blank");
    }
    if (scopes.stream().anyMatch(scope -> scope.length() > MAX_TEXT)) {
      throw new IllegalArgumentException(
          "An API key's scopes must be at most " + MAX_TEXT + " characters each");
    }
    Set<String> held = permissionsResolver.resolve(identity);
    List<String> missing = scopes.stream().filter(scope -> !held.contains(scope)).sorted().toList();
    if (!missing.isEmpty()) {
      throw new IllegalArgumentException(
          "An API key cannot hold a permission its owner does not: " + String.join(", ", missing));
    }
    return issue(identity.id(), name, lifetime, scopes);
  }

  /**
   * Lists an identity's keys, by name, expired ones included and marked.
   *
   * @param identityId the owner
   * @return the keys, with no secret in them
   */
  public List<ApiKey> findByIdentity(UUID identityId) {
    requireNonNull(identityId, "identityId must not be null");
    Instant now = clock.instant();
    return credentialIdentityRepository.findCredentialIdsByIdentityId(identityId).stream()
        .map(apiKeyCredentialRepository::findById)
        .flatMap(Optional::stream)
        .map(
            key ->
                new ApiKey(
                    key.id(),
                    key.name(),
                    key.scopes(),
                    key.createdAt(),
                    key.expiresAt(),
                    key.lastUsedAt(),
                    key.expiredAt(now)))
        .sorted(BY_NAME)
        .toList();
  }

  /**
   * Replaces a key without a hard cutover: the new key has the old one's name, scopes and lifetime
   * (at most the maximum), and the old key keeps working for the overlap, or until its own expiry
   * if that is sooner.
   *
   * @param keyId the key to replace
   * @param overlap how long the old key keeps working; zero to at most {@link #MAX_OVERLAP}
   * @return the new key, the only time it is shown
   * @throws IllegalArgumentException if the overlap is out of range, or the key is gone or expired
   */
  @Transactional
  public NewApiKey rotate(UUID keyId, Duration overlap) {
    requireNonNull(keyId, "keyId must not be null");
    requireNonNull(overlap, "overlap must not be null");
    if (overlap.isNegative() || overlap.compareTo(MAX_OVERLAP) > 0) {
      throw new IllegalArgumentException(
          "A rotation's overlap must be zero to " + MAX_OVERLAP + ", got " + overlap);
    }
    ApiKeyCredential old =
        apiKeyCredentialRepository
            .findById(keyId)
            .orElseThrow(() -> new IllegalArgumentException("No API key " + keyId));
    UUID owner =
        credentialIdentityRepository
            .findIdentityIdByCredentialId(keyId)
            .orElseThrow(() -> new IllegalArgumentException("No API key " + keyId));
    Instant now = clock.instant();
    if (old.expiredAt(now)) {
      throw new IllegalArgumentException("API key " + keyId + " has expired");
    }
    Duration lifetime = Duration.between(old.createdAt(), old.expiresAt());
    if (lifetime.compareTo(maxLifetime) > 0) {
      lifetime = maxLifetime;
    }
    // one conditional statement: never lengthens the key, and finds it revoked or expired since
    // the read above, before any replacement is made
    if (!apiKeyCredentialRepository.expireBy(keyId, now.plus(overlap), now)) {
      throw new IllegalArgumentException("API key " + keyId + " was revoked or has expired");
    }
    return issue(owner, old.name(), lifetime, old.scopes());
  }

  private NewApiKey issue(UUID ownerId, String name, Duration lifetime, Set<String> scopes) {
    String rawKey = format.generate();
    Instant now = clock.instant();
    ApiKeyCredential credential =
        new ApiKeyCredential(
            Identifiers.uuid(), name, sha256Hex(rawKey), scopes, now, now.plus(lifetime), null);
    apiKeyCredentialRepository.save(credential);
    credentialIdentityRepository.link(credential.id(), ownerId);
    eventPublisher.publish(
        new CredentialAdded(now, ownerId, credential.id(), credential.kind(), name));
    return new NewApiKey(
        credential.id(), name, rawKey, credential.scopes(), credential.expiresAt());
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
