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

import org.jwcarman.who.core.Identifiers;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * Service for creating and managing API key credentials.
 *
 * <p>Each key is a prefixed opaque string ({@code who_<64 hex chars>}) generated from 32 random
 * bytes. Only the SHA-256 hash of the raw key is stored — the raw key is returned once and never
 * persisted.
 */
public class ApiKeyService {

    private static final HexFormat HEX = HexFormat.of();

    private final ApiKeyCredentialRepository apiKeyCredentialRepository;
    private final CredentialIdentityRepository credentialIdentityRepository;
    private final SecureRandom secureRandom;

    /**
     * Creates a new {@code ApiKeyService}.
     *
     * @param apiKeyCredentialRepository  repository for API key credentials
     * @param credentialIdentityRepository repository for linking credentials to identities
     */
    public ApiKeyService(ApiKeyCredentialRepository apiKeyCredentialRepository,
                         CredentialIdentityRepository credentialIdentityRepository) {
        this.apiKeyCredentialRepository = requireNonNull(apiKeyCredentialRepository,
                "apiKeyCredentialRepository must not be null");
        this.credentialIdentityRepository = requireNonNull(credentialIdentityRepository,
                "credentialIdentityRepository must not be null");
        this.secureRandom = new SecureRandom();
    }

    /**
     * Generates a new API key for the given identity, stores its hash, and returns the raw key.
     *
     * <p>This is the only opportunity to retrieve the raw key — it is not stored anywhere.
     *
     * @param identityId the identity UUID to link the new credential to
     * @param name       a human-readable label for this key (e.g. "Production server")
     * @return the raw API key (e.g. {@code who_a3f8...}), never stored
     */
    public String create(UUID identityId, String name) {
        requireNonNull(identityId, "identityId must not be null");
        requireNonNull(name, "name must not be null");

        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String rawKey = "who_" + HEX.formatHex(bytes);
        String keyHash = sha256Hex(rawKey);

        ApiKeyCredential credential = new ApiKeyCredential(Identifiers.uuid(), name, keyHash);
        apiKeyCredentialRepository.save(credential);
        credentialIdentityRepository.link(credential.id(), identityId);

        return rawKey;
    }

    static String sha256Hex(String input) {
        return HEX.formatHex(MessageDigests.sha256(input.getBytes(StandardCharsets.UTF_8)));
    }
}
