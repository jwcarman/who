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

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;

/**
 * JDBC implementation of Spring Security's {@link PublicKeyCredentialUserEntityRepository} on
 * {@code who_passkey_user}. Each row is the WebAuthn user entity of one identity. Its {@code id}
 * (the user handle) is stored as Spring stores it, as {@link Bytes#toBase64UrlString()}.
 *
 * <p>Spring looks the entity up with {@link #findByUsername(String)}, passing {@code
 * Authentication.getName()}, which is the identity id. who creates the entity with {@link
 * #create(UUID, String)} before Spring looks, so the user handle is 64 random bytes.
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

  /**
   * Creates a new repository.
   *
   * @param jdbcClient the JDBC client used to execute queries
   */
  public JdbcWhoPasskeyUserRepository(JdbcClient jdbcClient) {
    this.jdbcClient = requireNonNull(jdbcClient, "jdbcClient must not be null");
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
    Optional<PublicKeyCredentialUserEntity> existing = findByIdentityId(identityId);
    if (existing.isPresent()) {
      return existing.get();
    }
    byte[] handle = new byte[USER_HANDLE_LENGTH];
    random.nextBytes(handle);
    PublicKeyCredentialUserEntity entity =
        ImmutablePublicKeyCredentialUserEntity.builder()
            .id(new Bytes(handle))
            .name(name)
            .displayName(name)
            .build();
    try {
      insert(entity, identityId);
    } catch (DuplicateKeyException _) {
      // A concurrent create for the same identity won.
      return findByIdentityId(identityId).orElseThrow();
    }
    return entity;
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
   * Updates the entity's {@code name} and {@code display_name}, or inserts it if no row has its id.
   * An insert takes the identity id from the entity's {@code name}, as Spring's own registration
   * creates entities named {@code Authentication.getName()}.
   *
   * @param userEntity the entity
   * @throws IllegalArgumentException if a new entity's name is not an identity id
   */
  @Override
  public void save(PublicKeyCredentialUserEntity userEntity) {
    int updated =
        jdbcClient
            .sql(
                "UPDATE who_passkey_user SET name = :name, display_name = :display_name"
                    + " WHERE id = :id")
            .param(COL_ID, userEntity.getId().toBase64UrlString())
            .param(COL_NAME, userEntity.getName())
            .param(COL_DISPLAY_NAME, userEntity.getDisplayName())
            .update();
    if (updated == 0) {
      UUID identityId =
          parseUuid(userEntity.getName())
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "a new user entity's name must be an identity id"));
      insert(userEntity, identityId);
    }
  }

  @Override
  public void delete(Bytes id) {
    jdbcClient
        .sql("DELETE FROM who_passkey_user WHERE id = :id")
        .param(COL_ID, id.toBase64UrlString())
        .update();
  }

  private Optional<PublicKeyCredentialUserEntity> findByIdentityId(UUID identityId) {
    return jdbcClient
        .sql(SELECT + " WHERE identity_id = :identity_id")
        .param(COL_IDENTITY_ID, identityId)
        .query(ROW_MAPPER)
        .optional();
  }

  private void insert(PublicKeyCredentialUserEntity entity, UUID identityId) {
    jdbcClient
        .sql(
            "INSERT INTO who_passkey_user (id, identity_id, name, display_name)"
                + " VALUES (:id, :identity_id, :name, :display_name)")
        .param(COL_ID, entity.getId().toBase64UrlString())
        .param(COL_IDENTITY_ID, identityId)
        .param(COL_NAME, entity.getName())
        .param(COL_DISPLAY_NAME, entity.getDisplayName())
        .update();
  }

  private static Optional<UUID> parseUuid(String value) {
    if (value == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(UUID.fromString(value));
    } catch (IllegalArgumentException _) {
      return Optional.empty();
    }
  }
}
