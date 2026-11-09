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
package org.jwcarman.who.core.service;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jwcarman.who.core.domain.Identity;
import org.jwcarman.who.core.domain.IdentityStatus;
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.core.spi.Credential;
import org.jwcarman.who.core.spi.CredentialRemover;
import org.jwcarman.who.core.spi.PermissionsResolver;
import org.springframework.transaction.annotation.Transactional;

/**
 * Core service that resolves a {@link Credential} to a {@link WhoPrincipal}.
 *
 * <p>Resolution pipeline:
 *
 * <ol>
 *   <li>Look up the credential UUID in {@link CredentialIdentityRepository}.
 *   <li>Return empty if not linked.
 *   <li>Load the identity from {@link IdentityRepository}.
 *   <li>Return empty if not found or status is not {@link IdentityStatus#ACTIVE}.
 *   <li>Resolve permissions via the registered {@link PermissionsResolver}.
 *   <li>Return a populated {@link WhoPrincipal}.
 * </ol>
 *
 * <p>It also deletes identities together with their credentials ({@link #deleteIdentity(UUID)}) and
 * purges credentials that are linked to no identity ({@link #purgeOrphanedCredentials()}), through
 * the {@link CredentialRemover}s it is given. Wiring is done by the autoconfigure module.
 *
 * <p>{@code who-core} needs no Spring at runtime. The {@code @Transactional} methods run in a
 * transaction when Spring's transaction support is present, as it always is with who's starter.
 */
public class WhoService {

  private final IdentityRepository identityRepository;
  private final CredentialIdentityRepository credentialIdentityRepository;
  private final PermissionsResolver permissionsResolver;
  private final List<CredentialRemover> credentialRemovers;

  /**
   * Constructs a {@code WhoService} without credential removers: {@link #deleteIdentity(UUID)} then
   * deletes only links, and {@link #purgeOrphanedCredentials()} deletes nothing.
   *
   * @param identityRepository stores and retrieves identities
   * @param credentialIdentityRepository maps credential UUIDs to identity UUIDs
   * @param permissionsResolver resolves permissions for an active identity
   */
  public WhoService(
      IdentityRepository identityRepository,
      CredentialIdentityRepository credentialIdentityRepository,
      PermissionsResolver permissionsResolver) {
    this(identityRepository, credentialIdentityRepository, permissionsResolver, List.of());
  }

  /**
   * Constructs a {@code WhoService} with its collaborators.
   *
   * @param identityRepository stores and retrieves identities
   * @param credentialIdentityRepository maps credential UUIDs to identity UUIDs
   * @param permissionsResolver resolves permissions for an active identity
   * @param credentialRemovers one per credential module; delete that module's credential rows
   */
  public WhoService(
      IdentityRepository identityRepository,
      CredentialIdentityRepository credentialIdentityRepository,
      PermissionsResolver permissionsResolver,
      List<CredentialRemover> credentialRemovers) {
    this.identityRepository =
        requireNonNull(identityRepository, "identityRepository must not be null");
    this.credentialIdentityRepository =
        requireNonNull(
            credentialIdentityRepository, "credentialIdentityRepository must not be null");
    this.permissionsResolver =
        requireNonNull(permissionsResolver, "permissionsResolver must not be null");
    this.credentialRemovers =
        List.copyOf(requireNonNull(credentialRemovers, "credentialRemovers must not be null"));
  }

  /**
   * Creates a new {@link Identity} with a generated UUID and {@link IdentityStatus#ACTIVE} status.
   *
   * @return the persisted identity
   */
  public Identity createIdentity() {
    return identityRepository.save(Identity.create());
  }

  /**
   * Resolves the given credential to a {@link WhoPrincipal}.
   *
   * @param credential the incoming credential
   * @return the resolved principal, or empty if the credential is not linked to an active identity
   */
  public Optional<WhoPrincipal> resolve(Credential credential) {
    return credentialIdentityRepository
        .findIdentityIdByCredentialId(credential.id())
        .flatMap(identityRepository::findById)
        .filter(identity -> identity.status() == IdentityStatus.ACTIVE)
        .map(identity -> new WhoPrincipal(identity, permissionsResolver.resolve(identity)));
  }

  /**
   * Deactivates an identity: sets it {@link IdentityStatus#DISABLED}. It keeps its credentials,
   * roles and history, but no credential resolves to it ({@link #resolve(Credential)}), so its next
   * request is unauthenticated, and its sign-in links and passkeys are refused, until it is
   * {@linkplain #reactivate(UUID) reactivated}. Deactivating an inactive identity changes nothing.
   *
   * @param identityId the identity to deactivate
   * @return {@code true} if the identity exists, {@code false} if there is no such identity
   */
  @Transactional
  public boolean deactivate(UUID identityId) {
    return setStatus(identityId, IdentityStatus.DISABLED);
  }

  /**
   * Reactivates an identity: sets it {@link IdentityStatus#ACTIVE}, so its credentials resolve to
   * it again. Reactivating an active identity changes nothing.
   *
   * @param identityId the identity to reactivate
   * @return {@code true} if the identity exists, {@code false} if there is no such identity
   */
  @Transactional
  public boolean reactivate(UUID identityId) {
    return setStatus(identityId, IdentityStatus.ACTIVE);
  }

  private boolean setStatus(UUID identityId, IdentityStatus status) {
    requireNonNull(identityId, "identityId must not be null");
    return identityRepository
        .findById(identityId)
        .map(identity -> identityRepository.save(identity.withStatus(status)))
        .isPresent();
  }

  /**
   * Deletes an identity together with its credentials. Each credential linked to the identity is
   * offered to the credential removers until one deletes it, then unlinked. Deleting the identity
   * then removes, by cascade, its role assignments, enrollment tokens and passkeys.
   *
   * <p>Prefer this to deleting the {@code who_identity} row directly: credential rows do not
   * reference the identity, so a direct delete leaves them behind (see {@link
   * #purgeOrphanedCredentials()}).
   *
   * @param identityId the identity to delete
   * @return {@code true} if the identity was deleted, {@code false} if there is no such identity
   */
  @Transactional
  public boolean deleteIdentity(UUID identityId) {
    requireNonNull(identityId, "identityId must not be null");
    if (!identityRepository.existsById(identityId)) {
      return false;
    }
    for (UUID credentialId :
        credentialIdentityRepository.findCredentialIdsByIdentityId(identityId)) {
      removeCredential(credentialId);
      credentialIdentityRepository.unlink(credentialId);
    }
    identityRepository.deleteById(identityId);
    return true;
  }

  /**
   * Deletes every credential that is linked to no identity, such as those left behind when an
   * identity row was deleted directly instead of through {@link #deleteIdentity(UUID)}.
   *
   * <p>A credential saved for enrollment but not yet enrolled is linked to no identity either, so
   * it is deleted too, as is a credential being registered between its save and its link. Run this
   * when no enrollment or registration is in flight.
   *
   * @return the number of credentials deleted
   */
  @Transactional
  public int purgeOrphanedCredentials() {
    return credentialRemovers.stream().mapToInt(CredentialRemover::removeUnlinked).sum();
  }

  private void removeCredential(UUID credentialId) {
    for (CredentialRemover remover : credentialRemovers) {
      if (remover.remove(credentialId)) {
        return;
      }
    }
  }
}
