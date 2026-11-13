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

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.jwcarman.who.core.event.CredentialRevoked;
import org.jwcarman.who.core.event.WhoEventPublisher;
import org.jwcarman.who.core.id.Identifiers;
import org.jwcarman.who.core.repository.CredentialIdentityRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.web.webauthn.api.AuthenticatorTransport;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutableCredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCose;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialType;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC implementation of Spring Security's {@link UserCredentialRepository} on {@code
 * who_passkey_credential}. The columns and encodings are Spring's: ids are {@link
 * Bytes#toBase64UrlString()}, transports are comma-joined. Two things differ:
 *
 * <ul>
 *   <li>Each row also has a {@code credential_uuid}: the id of its {@link PasskeyCredential}, which
 *       is linked to the identity that owns the passkey.
 *   <li>Empty transports are stored as {@code NULL}, not {@code ""}.
 * </ul>
 *
 * <p>{@code created} is required; {@code last_used} may be {@code NULL}. Spring's registration
 * always sets both (its builder defaults {@code lastUsed} to {@code created}), but the mapper
 * accepts a missing {@code last_used} rather than failing.
 *
 * <p>Deleting a record publishes {@link CredentialRevoked}, whoever deletes it: Spring's {@code
 * DELETE /webauthn/register/{id}} calls {@link #delete(Bytes)} directly.
 */
public class JdbcWhoPasskeyCredentialRepository implements UserCredentialRepository {

  private static final String COL_CREDENTIAL_ID = "credential_id";
  private static final String COL_USER_ENTITY_USER_ID = "user_entity_user_id";
  private static final String COL_PUBLIC_KEY = "public_key";
  private static final String COL_SIGNATURE_COUNT = "signature_count";
  private static final String COL_UV_INITIALIZED = "uv_initialized";
  private static final String COL_BACKUP_ELIGIBLE = "backup_eligible";
  private static final String COL_TRANSPORTS = "authenticator_transports";
  private static final String COL_CREDENTIAL_TYPE = "public_key_credential_type";
  private static final String COL_BACKUP_STATE = "backup_state";
  private static final String COL_ATTESTATION_OBJECT = "attestation_object";
  private static final String COL_ATTESTATION_CLIENT_DATA_JSON = "attestation_client_data_json";
  private static final String COL_CREATED = "created";
  private static final String COL_LAST_USED = "last_used";
  private static final String COL_LABEL = "label";
  private static final String COL_CREDENTIAL_UUID = "credential_uuid";
  private static final String COL_IDENTITY_ID = "identity_id";

  private static final String TRANSPORT_SEPARATOR = ",";

  /** Bound while a passkey is being registered: the only time {@link #save} inserts. */
  private static final ScopedValue<Boolean> REGISTERING = ScopedValue.newInstance();

  private static final String SELECT =
      "SELECT credential_id, user_entity_user_id, public_key, signature_count, uv_initialized,"
          + " backup_eligible, authenticator_transports, public_key_credential_type, backup_state,"
          + " attestation_object, attestation_client_data_json, created, last_used, label"
          + " FROM who_passkey_credential";

  private static final RowMapper<CredentialRecord> ROW_MAPPER =
      (rs, rowNum) ->
          ImmutableCredentialRecord.builder()
              .credentialId(Bytes.fromBase64(rs.getString(COL_CREDENTIAL_ID)))
              .userEntityUserId(Bytes.fromBase64(rs.getString(COL_USER_ENTITY_USER_ID)))
              .publicKey(new ImmutablePublicKeyCose(rs.getBytes(COL_PUBLIC_KEY)))
              .signatureCount(rs.getLong(COL_SIGNATURE_COUNT))
              .uvInitialized(rs.getBoolean(COL_UV_INITIALIZED))
              .backupEligible(rs.getBoolean(COL_BACKUP_ELIGIBLE))
              .transports(readTransports(rs.getString(COL_TRANSPORTS)))
              .credentialType(readCredentialType(rs.getString(COL_CREDENTIAL_TYPE)))
              .backupState(rs.getBoolean(COL_BACKUP_STATE))
              .attestationObject(readBytes(rs, COL_ATTESTATION_OBJECT))
              .attestationClientDataJSON(readBytes(rs, COL_ATTESTATION_CLIENT_DATA_JSON))
              .created(readInstant(rs, COL_CREATED))
              .lastUsed(readInstant(rs, COL_LAST_USED))
              .label(rs.getString(COL_LABEL))
              .build();

  private static final RowMapper<PasskeyCredential> PASSKEY_MAPPER =
      (rs, rowNum) ->
          new PasskeyCredential(
              rs.getObject(COL_CREDENTIAL_UUID, UUID.class), rs.getString(COL_LABEL));

  private static final RowMapper<PasskeySummary> SUMMARY_MAPPER =
      (rs, rowNum) ->
          new PasskeySummary(
              rs.getObject(COL_CREDENTIAL_UUID, UUID.class),
              rs.getString(COL_CREDENTIAL_ID),
              rs.getString(COL_LABEL),
              readInstant(rs, COL_CREATED),
              readInstant(rs, COL_LAST_USED));

  private final JdbcClient jdbcClient;
  private final CredentialIdentityRepository credentialIdentityRepository;
  private final WhoEventPublisher eventPublisher;
  private final Clock clock;

  /**
   * Creates a new repository that publishes no events.
   *
   * @param jdbcClient the JDBC client used to execute queries
   * @param credentialIdentityRepository links each passkey to its identity
   */
  public JdbcWhoPasskeyCredentialRepository(
      JdbcClient jdbcClient, CredentialIdentityRepository credentialIdentityRepository) {
    this(jdbcClient, credentialIdentityRepository, WhoEventPublisher.NOOP, Clock.systemUTC());
  }

  /**
   * Creates a new repository.
   *
   * @param jdbcClient the JDBC client used to execute queries
   * @param credentialIdentityRepository links each passkey to its identity
   * @param eventPublisher where {@link CredentialRevoked} is published
   * @param clock stamps the events
   */
  public JdbcWhoPasskeyCredentialRepository(
      JdbcClient jdbcClient,
      CredentialIdentityRepository credentialIdentityRepository,
      WhoEventPublisher eventPublisher,
      Clock clock) {
    this.jdbcClient = requireNonNull(jdbcClient, "jdbcClient must not be null");
    this.credentialIdentityRepository =
        requireNonNull(
            credentialIdentityRepository, "credentialIdentityRepository must not be null");
    this.eventPublisher = requireNonNull(eventPublisher, "eventPublisher must not be null");
    this.clock = requireNonNull(clock, "clock must not be null");
  }

  /**
   * Saves a credential record. Spring calls this in two cases:
   *
   * <ul>
   *   <li>At registration, when no row has the credential id: inserts the row with a new {@code
   *       credential_uuid} and links that {@link PasskeyCredential} to the identity that owns the
   *       record's user entity. who's relying party marks the registration (see {@link
   *       #registering(Supplier)}); a save outside it never inserts.
   *   <li>At every sign-in, when the row exists: updates {@code signature_count}, {@code
   *       last_used}, {@code uv_initialized} and {@code backup_state} only, so the {@code
   *       credential_uuid} and its link are unchanged.
   * </ul>
   *
   * <p>A save for an unknown credential outside a registration does nothing. That is a sign-in
   * whose passkey was revoked after Spring read it: the passkey stays revoked, and the sign-in then
   * fails because the credential maps to no identity.
   *
   * @param credentialRecord the record
   * @throws IllegalStateException if a new record's user entity does not exist
   */
  @Override
  @Transactional
  public void save(CredentialRecord credentialRecord) {
    String credentialId = credentialRecord.getCredentialId().toBase64UrlString();
    if (findCredentialUuid(credentialId).isPresent()) {
      update(credentialId, credentialRecord);
    } else if (REGISTERING.isBound()) {
      insert(credentialId, credentialRecord);
    }
  }

  /**
   * Runs a registration: while it runs, {@link #save} may insert a new credential record on this
   * thread. Used by {@link WhoRelyingPartyOperations}.
   *
   * @param registration the registration
   * @param <R> the registration's result
   * @return the registration's result
   */
  static <R> R registering(Supplier<R> registration) {
    return ScopedValue.where(REGISTERING, Boolean.TRUE).call(registration::get);
  }

  /**
   * Deletes a credential record and unlinks its {@link PasskeyCredential}, then publishes {@link
   * CredentialRevoked} if it was linked to an identity. Does nothing if no row has the credential
   * id.
   *
   * @param credentialId the credential id
   */
  @Override
  @Transactional
  public void delete(Bytes credentialId) {
    String id = credentialId.toBase64UrlString();
    findPasskeyByCredentialId(id)
        .ifPresent(
            passkey -> {
              // looked up before unlinking, which removes the only record of the owner
              Optional<UUID> owner =
                  credentialIdentityRepository.findIdentityIdByCredentialId(passkey.id());
              jdbcClient
                  .sql("DELETE FROM who_passkey_credential WHERE credential_id = :credential_id")
                  .param(COL_CREDENTIAL_ID, id)
                  .update();
              credentialIdentityRepository.unlink(passkey.id());
              owner.ifPresent(
                  identityId ->
                      eventPublisher.publish(
                          new CredentialRevoked(
                              clock.instant(),
                              identityId,
                              passkey.id(),
                              passkey.kind(),
                              passkey.label().orElse(null))));
            });
  }

  @Override
  public CredentialRecord findByCredentialId(Bytes credentialId) {
    return jdbcClient
        .sql(SELECT + " WHERE credential_id = :credential_id")
        .param(COL_CREDENTIAL_ID, credentialId.toBase64UrlString())
        .query(ROW_MAPPER)
        .optional()
        .orElse(null);
  }

  @Override
  public List<CredentialRecord> findByUserId(Bytes userId) {
    return jdbcClient
        .sql(SELECT + " WHERE user_entity_user_id = :user_entity_user_id ORDER BY created")
        .param(COL_USER_ENTITY_USER_ID, userId.toBase64UrlString())
        .query(ROW_MAPPER)
        .list();
  }

  /**
   * Finds the {@code credential_uuid} of a credential id.
   *
   * @param credentialId the WebAuthn credential id
   * @return the credential UUID, or empty if no row has the credential id
   */
  public Optional<UUID> credentialUuid(Bytes credentialId) {
    requireNonNull(credentialId, "credentialId must not be null");
    return findCredentialUuid(credentialId.toBase64UrlString());
  }

  /**
   * Finds the passkey with a {@code credential_uuid}, with its label.
   *
   * @param credentialUuid the credential UUID
   * @return the passkey, or empty if no row has the UUID
   */
  public Optional<PasskeyCredential> findPasskey(UUID credentialUuid) {
    requireNonNull(credentialUuid, "credentialUuid must not be null");
    return jdbcClient
        .sql(
            "SELECT credential_uuid, label FROM who_passkey_credential"
                + " WHERE credential_uuid = :credential_uuid")
        .param(COL_CREDENTIAL_UUID, credentialUuid)
        .query(PASSKEY_MAPPER)
        .optional();
  }

  /**
   * Finds the WebAuthn credential id of a {@code credential_uuid}.
   *
   * @param credentialUuid the credential UUID
   * @return the credential id, or empty if no row has the UUID
   */
  public Optional<Bytes> findCredentialId(UUID credentialUuid) {
    requireNonNull(credentialUuid, "credentialUuid must not be null");
    return jdbcClient
        .sql(
            "SELECT credential_id FROM who_passkey_credential"
                + " WHERE credential_uuid = :credential_uuid")
        .param(COL_CREDENTIAL_UUID, credentialUuid)
        .query(String.class)
        .optional()
        .map(Bytes::fromBase64);
  }

  /**
   * Lists the passkeys of an identity, oldest first.
   *
   * @param identityId the identity
   * @return the identity's passkeys
   */
  public List<PasskeySummary> findSummariesByIdentityId(UUID identityId) {
    requireNonNull(identityId, "identityId must not be null");
    return jdbcClient
        .sql(
            "SELECT c.credential_uuid, c.credential_id, c.label, c.created, c.last_used"
                + " FROM who_passkey_credential c"
                + " JOIN who_passkey_user u ON u.id = c.user_entity_user_id"
                + " WHERE u.identity_id = :identity_id"
                + " ORDER BY c.created, c.credential_uuid")
        .param(COL_IDENTITY_ID, identityId)
        .query(SUMMARY_MAPPER)
        .list();
  }

  private Optional<PasskeyCredential> findPasskeyByCredentialId(String credentialId) {
    return jdbcClient
        .sql(
            "SELECT credential_uuid, label FROM who_passkey_credential"
                + " WHERE credential_id = :credential_id")
        .param(COL_CREDENTIAL_ID, credentialId)
        .query(PASSKEY_MAPPER)
        .optional();
  }

  private Optional<UUID> findCredentialUuid(String credentialId) {
    return jdbcClient
        .sql(
            "SELECT credential_uuid FROM who_passkey_credential"
                + " WHERE credential_id = :credential_id")
        .param(COL_CREDENTIAL_ID, credentialId)
        .query(UUID.class)
        .optional();
  }

  private void update(String credentialId, CredentialRecord credentialRecord) {
    jdbcClient
        .sql(
            "UPDATE who_passkey_credential SET signature_count = :signature_count,"
                + " last_used = :last_used, uv_initialized = :uv_initialized,"
                + " backup_state = :backup_state WHERE credential_id = :credential_id")
        .param(COL_SIGNATURE_COUNT, credentialRecord.getSignatureCount())
        .param(COL_LAST_USED, toOffsetDateTime(credentialRecord.getLastUsed()))
        .param(COL_UV_INITIALIZED, credentialRecord.isUvInitialized())
        .param(COL_BACKUP_STATE, credentialRecord.isBackupState())
        .param(COL_CREDENTIAL_ID, credentialId)
        .update();
  }

  private void insert(String credentialId, CredentialRecord credentialRecord) {
    String userEntityUserId = credentialRecord.getUserEntityUserId().toBase64UrlString();
    UUID identityId =
        jdbcClient
            .sql("SELECT identity_id FROM who_passkey_user WHERE id = :id")
            .param("id", userEntityUserId)
            .query(UUID.class)
            .optional()
            .orElseThrow(
                () -> new IllegalStateException("the credential's user entity does not exist"));
    PasskeyCredential passkey =
        new PasskeyCredential(Identifiers.uuid(), credentialRecord.getLabel());
    jdbcClient
        .sql(
            "INSERT INTO who_passkey_credential (credential_id, user_entity_user_id, public_key,"
                + " signature_count, uv_initialized, backup_eligible, authenticator_transports,"
                + " public_key_credential_type, backup_state, attestation_object,"
                + " attestation_client_data_json, created, last_used, label, credential_uuid)"
                + " VALUES (:credential_id, :user_entity_user_id, :public_key, :signature_count,"
                + " :uv_initialized, :backup_eligible, :authenticator_transports,"
                + " :public_key_credential_type, :backup_state, :attestation_object,"
                + " :attestation_client_data_json, :created, :last_used, :label,"
                + " :credential_uuid)")
        .param(COL_CREDENTIAL_ID, credentialId)
        .param(COL_USER_ENTITY_USER_ID, userEntityUserId)
        .param(COL_PUBLIC_KEY, credentialRecord.getPublicKey().getBytes())
        .param(COL_SIGNATURE_COUNT, credentialRecord.getSignatureCount())
        .param(COL_UV_INITIALIZED, credentialRecord.isUvInitialized())
        .param(COL_BACKUP_ELIGIBLE, credentialRecord.isBackupEligible())
        .param(COL_TRANSPORTS, writeTransports(credentialRecord.getTransports()))
        .param(COL_CREDENTIAL_TYPE, writeCredentialType(credentialRecord.getCredentialType()))
        .param(COL_BACKUP_STATE, credentialRecord.isBackupState())
        .param(COL_ATTESTATION_OBJECT, writeBytes(credentialRecord.getAttestationObject()))
        .param(
            COL_ATTESTATION_CLIENT_DATA_JSON,
            writeBytes(credentialRecord.getAttestationClientDataJSON()))
        .param(COL_CREATED, toOffsetDateTime(credentialRecord.getCreated()))
        .param(COL_LAST_USED, toOffsetDateTime(credentialRecord.getLastUsed()))
        .param(COL_LABEL, credentialRecord.getLabel())
        .param(COL_CREDENTIAL_UUID, passkey.id())
        .update();
    credentialIdentityRepository.link(passkey.id(), identityId);
  }

  private static String writeTransports(Set<AuthenticatorTransport> transports) {
    if (transports.isEmpty()) {
      return null;
    }
    return transports.stream()
        .map(AuthenticatorTransport::getValue)
        .sorted()
        .collect(Collectors.joining(TRANSPORT_SEPARATOR));
  }

  private static Set<AuthenticatorTransport> readTransports(String transports) {
    Set<AuthenticatorTransport> result = new HashSet<>();
    if (transports != null) {
      Arrays.stream(transports.split(TRANSPORT_SEPARATOR))
          .map(AuthenticatorTransport::valueOf)
          .forEach(result::add);
    }
    return result;
  }

  private static String writeCredentialType(PublicKeyCredentialType credentialType) {
    return credentialType != null ? credentialType.getValue() : null;
  }

  private static PublicKeyCredentialType readCredentialType(String credentialType) {
    return credentialType != null ? PublicKeyCredentialType.valueOf(credentialType) : null;
  }

  private static byte[] writeBytes(Bytes bytes) {
    return bytes != null ? bytes.getBytes() : null;
  }

  private static Bytes readBytes(ResultSet rs, String column) throws SQLException {
    byte[] bytes = rs.getBytes(column);
    return bytes != null ? new Bytes(bytes) : null;
  }

  private static OffsetDateTime toOffsetDateTime(Instant instant) {
    return instant != null ? instant.atOffset(ZoneOffset.UTC) : null;
  }

  private static Instant readInstant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value != null ? value.toInstant() : null;
  }
}
