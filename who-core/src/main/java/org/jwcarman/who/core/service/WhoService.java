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

import org.jwcarman.who.core.domain.IdentityStatus;
import org.jwcarman.who.core.domain.WhoPrincipal;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.jwcarman.who.core.repository.IdentityRepository;
import org.jwcarman.who.core.spi.Credential;
import org.jwcarman.who.core.spi.PermissionsResolver;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Core service that resolves a {@link Credential} to a {@link WhoPrincipal}.
 *
 * <p>Resolution pipeline:
 * <ol>
 *   <li>Look up the credential UUID in {@link CredentialIdentityRepository}.</li>
 *   <li>Return empty if not linked.</li>
 *   <li>Load the identity from {@link IdentityRepository}.</li>
 *   <li>Return empty if not found or status is not {@link IdentityStatus#ACTIVE}.</li>
 *   <li>Resolve permissions via the registered {@link PermissionsResolver}.</li>
 *   <li>Return a populated {@link WhoPrincipal}.</li>
 * </ol>
 *
 * <p>This is a plain Java class with no Spring annotations. Wiring is done by the
 * autoconfigure module.
 */
public class WhoService {

    private final IdentityRepository identityRepository;
    private final CredentialIdentityRepository credentialIdentityRepository;
    private final PermissionsResolver permissionsResolver;

    /**
     * Constructs a {@code WhoService} with its required collaborators.
     *
     * @param identityRepository           stores and retrieves identities
     * @param credentialIdentityRepository maps credential UUIDs to identity UUIDs
     * @param permissionsResolver          resolves permissions for an active identity
     */
    public WhoService(IdentityRepository identityRepository,
                      CredentialIdentityRepository credentialIdentityRepository,
                      PermissionsResolver permissionsResolver) {
        this.identityRepository = requireNonNull(identityRepository, "identityRepository must not be null");
        this.credentialIdentityRepository = requireNonNull(credentialIdentityRepository,
                "credentialIdentityRepository must not be null");
        this.permissionsResolver = requireNonNull(permissionsResolver, "permissionsResolver must not be null");
    }

    /**
     * Resolves the given credential to a {@link WhoPrincipal}.
     *
     * @param credential the incoming credential
     * @return the resolved principal, or empty if the credential is not linked to an active identity
     */
    public Optional<WhoPrincipal> resolve(Credential credential) {
        return credentialIdentityRepository.findIdentityIdByCredentialId(credential.id())
                .flatMap(identityRepository::findById)
                .filter(identity -> identity.status() == IdentityStatus.ACTIVE)
                .map(identity -> new WhoPrincipal(
                        identity.id(),
                        permissionsResolver.resolve(identity)
                ));
    }
}
