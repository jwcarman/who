CREATE TABLE IF NOT EXISTS who_passkey_user (
    id           VARCHAR(1000) PRIMARY KEY,
    identity_id  UUID          NOT NULL UNIQUE REFERENCES who_identity (id) ON DELETE CASCADE,
    name         VARCHAR(320)  NOT NULL,
    display_name VARCHAR(320)
);

CREATE TABLE IF NOT EXISTS who_passkey_credential (
    credential_id                VARCHAR(1000)            PRIMARY KEY,
    user_entity_user_id          VARCHAR(1000)            NOT NULL REFERENCES who_passkey_user (id) ON DELETE CASCADE,
    public_key                   BYTEA                    NOT NULL,
    signature_count              BIGINT,
    uv_initialized               BOOLEAN,
    backup_eligible              BOOLEAN                  NOT NULL,
    authenticator_transports     VARCHAR(1000),
    public_key_credential_type   VARCHAR(100),
    backup_state                 BOOLEAN                  NOT NULL,
    attestation_object           BYTEA,
    attestation_client_data_json BYTEA,
    created                      TIMESTAMP WITH TIME ZONE NOT NULL,
    last_used                    TIMESTAMP WITH TIME ZONE,
    label                        VARCHAR(1000)            NOT NULL,
    credential_uuid              UUID                     NOT NULL UNIQUE
);
