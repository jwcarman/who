CREATE TABLE IF NOT EXISTS who_api_key_credential (
    id           UUID                     PRIMARY KEY,
    name         VARCHAR(255)             NOT NULL,
    key_hash     VARCHAR(64)              NOT NULL UNIQUE,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    last_used_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE IF NOT EXISTS who_api_key_scope (
    credential_id UUID         NOT NULL REFERENCES who_api_key_credential (id) ON DELETE CASCADE,
    permission    VARCHAR(255) NOT NULL,
    PRIMARY KEY (credential_id, permission)
);
