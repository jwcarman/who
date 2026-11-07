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

import static java.util.Objects.requireNonNull;

import java.security.SecureRandom;
import java.util.Optional;
import java.util.UUID;

import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC implementation of Spring Security's {@link PublicKeyCredentialUserEntityRepository} on
 * {@code who_passkey_user}. Each row is the WebAuthn user entity of one identity. Its {@code id}
 * (the user handle) is stored as Spring stores it, as {@link Bytes#toBase64UrlString()}.
 *
 * <p>Spring looks the entity up with {@link #findByUsername(String)}, passing {@code
 * Authentication.getName()}, which is the identity id. who creates the entity with {@link
 * #create(UUID, String)} before Spring looks, so the user handle is 64 random bytes.
 *
 * <p>The passkey module has no {@code CredentialRemover}: {@code who_passkey_user} references
 * {@code who_identity} with {@code ON DELETE CASCADE}, and {@code who_passkey_credential}
 * references {@code who_passkey_user} the same way, so deleting an identity deletes its passkeys.
 */
public class JdbcWhoPasskeyUserRepository implements PublicKeyCredentialUserEntityRepository {

  private static final int USER_HANDLE_LENGTH = 64;

  private static final String COL_ID = "id";
  private static final String COL_IDENTITY_ID = "identity_id";
  private static final String COL_NAME = "name";
  private static final String COL_DISPLAY_NAME = "display_name";

  private static final String SELECT = "SELECT id, name, display_name FROM who_passkey_user";

  private static final RowMapper<PublicKeyCredentialUserEntity> ROW_MAPPER =
      (rs, rowNum) ->
          ImmutablePublicKeyCredentialUserEntity.builder()
              .id(Bytes.fromBase64(rs.getString(COL_ID)))
              .name(rs.getString(COL_NAME))
              .displayName(rs.getString(COL_DISPLAY_NAME))
              .build();

  private final SecureRandom random = new SecureRandom();
  private final JdbcClient jdbcClient;
  private final CredentialIdentityRepository credentialIdentityRepository;

  /**
   * Creates a new repository.
   *
   * @param jdbcClient the JDBC client used to execute queries
   * @param credentialIdentityRepository unlinks an entity's passkeys when the entity is deleted
   */
  public JdbcWhoPasskeyUserRepository(
      JdbcClient jdbcClient, CredentialIdentityRepository credentialIdentityRepository) {
    this.jdbcClient = requireNonNull(jdbcClient, "jdbcClient must not be null");
    this.credentialIdentityRepository =
        requireNonNull(
            credentialIdentityRepository, "credentialIdentityRepository must not be null");
  }

  /**
   * Returns the identity's user entity, creating it if it does not exist. A new entity's user
   * handle is 64 bytes from {@link SecureRandom}; its {@code name} and {@code display_name} are
   * {@code name}. An existing entity is returned unchanged.
   *
   * @param identityId the identity
   * @param name the name the passkey picker shows
   * @return the identity's user entity
   */
  public PublicKeyCredentialUserEntity create(UUID identityId, String name) {
    requireNonNull(identityId, "identityId must not be null");
    requireNonNull(name, "name must not be null");
    byte[] handle = new byte[USER_HANDLE_LENGTH];
    random.nextBytes(handle);
    // ON CONFLICT keeps a concurrent create from aborting the caller's transaction.
    jdbcClient
        .sql(
            "INSERT INTO who_passkey_user (id, identity_id, name, display_name)"
                + " VALUES (:id, :identity_id, :name, :display_name)"
                + " ON CONFLICT (identity_id) DO NOTHING")
        .param(COL_ID, new Bytes(handle).toBase64UrlString())
        .param(COL_IDENTITY_ID, identityId)
        .param(COL_NAME, name)
        .param(COL_DISPLAY_NAME, name)
        .update();
    return findByIdentityId(identityId).orElseThrow();
  }

  /**
   * Sets the {@code name} and {@code display_name} of the identity's user entity. Does nothing if
   * the identity has none. The user handle is unchanged.
   *
   * @param identityId the identity
   * @param name the name the passkey picker shows
   */
  public void rename(UUID identityId, String name) {
    requireNonNull(identityId, "identityId must not be null");
    requireNonNull(name, "name must not be null");
    jdbcClient
        .sql(
            "UPDATE who_passkey_user SET name = :name, display_name = :display_name"
                + " WHERE identity_id = :identity_id")
        .param(COL_NAME, name)
        .param(COL_DISPLAY_NAME, name)
        .param(COL_IDENTITY_ID, identityId)
        .update();
  }

  /**
   * Finds the identity whose user entity has the given user handle.
   *
   * @param userHandle the user handle
   * @return the identity id, or empty if no entity has that handle
   */
  public Optional<UUID> findIdentityId(Bytes userHandle) {
    requireNonNull(userHandle, "userHandle must not be null");
    return jdbcClient
        .sql("SELECT identity_id FROM who_passkey_user WHERE id = :id")
        .param(COL_ID, userHandle.toBase64UrlString())
        .query(UUID.class)
        .optional();
  }

  @Override
  public PublicKeyCredentialUserEntity findById(Bytes id) {
    return jdbcClient
        .sql(SELECT + " WHERE id = :id")
        .param(COL_ID, id.toBase64UrlString())
        .query(ROW_MAPPER)
        .optional()
        .orElse(null);
  }

  /**
   * Finds the user entity of an identity.
   *
   * @param username the identity id, as Spring passes {@code Authentication.getName()}
   * @return the entity, or {@code null} if there is none or {@code username} is not a UUID
   */
  @Override
  public PublicKeyCredentialUserEntity findByUsername(String username) {
    return parseUuid(username).flatMap(this::findByIdentityId).orElse(null);
  }

  /**
   * Upserts the entity on its id: inserts it, or updates {@code name} and {@code display_name}. The
   * identity id comes from the existing row, or for a new entity from its {@code name}, as Spring's
   * own registration creates entities named {@code Authentication.getName()}.
   *
   * @param userEntity the entity
   * @throws IllegalArgumentException if a new entity's name is not an identity id
   */
  @Override
  public void save(PublicKeyCredentialUserEntity userEntity) {
    UUID identityId =
        parseUuid(userEntity.getName())
            .or(() -> findIdentityId(userEntity.getId()))
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "a new user entity's name must be an identity id"));
    jdbcClient
        .sql(
            "INSERT INTO who_passkey_user (id, identity_id, name, display_name)"
                + " VALUES (:id, :identity_id, :name, :display_name)"
                + " ON CONFLICT (id) DO UPDATE"
                + " SET name = EXCLUDED.name, display_name = EXCLUDED.display_name")
        .param(COL_ID, userEntity.getId().toBase64UrlString())
        .param(COL_IDENTITY_ID, identityId)
        .param(COL_NAME, userEntity.getName())
        .param(COL_DISPLAY_NAME, userEntity.getDisplayName())
        .update();
  }

  /**
   * Deletes the entity. Its passkeys are deleted by the foreign key cascade; this method unlinks
   * each of them from the identity first, so no session signed in with one stays valid.
   *
   * @param id the user handle
   */
  @Override
  @Transactional
  public void delete(Bytes id) {
    String handle = id.toBase64UrlString();
    jdbcClient
        .sql(
            "SELECT credential_uuid FROM who_passkey_credential"
                + " WHERE user_entity_user_id = :id")
        .param(COL_ID, handle)
        .query(UUID.class)
        .list()
        .forEach(credentialIdentityRepository::unlink);
    jdbcClient.sql("DELETE FROM who_passkey_user WHERE id = :id").param(COL_ID, handle).update();
  }

  private Optional<PublicKeyCredentialUserEntity> findByIdentityId(UUID identityId) {
    return jdbcClient
        .sql(SELECT + " WHERE identity_id = :identity_id")
        .param(COL_IDENTITY_ID, identityId)
        .query(ROW_MAPPER)
        .optional();
  }

  private static Optional<UUID> parseUuid(String value) {
    try {
      return Optional.of(UUID.fromString(value));
    } catch (IllegalArgumentException _) {
      return Optional.empty();
    }
  }
}
